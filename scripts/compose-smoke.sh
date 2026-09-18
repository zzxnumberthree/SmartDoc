#!/usr/bin/env bash
set -Eeuo pipefail

for required_command in docker curl jq; do
  if ! command -v "${required_command}" >/dev/null 2>&1; then
    echo "Required command not found: ${required_command}" >&2
    exit 1
  fi
done

for required_variable in GOOGLE_API_KEY DB_ROOT_PASSWORD DB_PASSWORD JWT_SECRET; do
  if [[ -z "${!required_variable:-}" ]]; then
    echo "Required environment variable is empty: ${required_variable}" >&2
    exit 1
  fi
done

if [[ ${#JWT_SECRET} -lt 32 ]]; then
  echo "JWT_SECRET must contain at least 32 characters." >&2
  exit 1
fi

readonly base_url="http://localhost:${SMARTDOC_PORT:-8080}"
readonly marker="SMARTDOC_COMPOSE_SMOKE_EVIDENCE"
readonly smoke_username="smartdoc-smoke-$(date +%s)-$$"
readonly smoke_password="SmartDoc-Smoke-$(date +%s)-$$"
readonly compose_project_name="smartdoc-smoke-$(date +%s)-$$"
compose_command=(docker compose --project-name "${compose_project_name}")
temporary_directory="$(mktemp -d)"
keep_stack=false

if [[ "${1:-}" == "--keep" ]]; then
  keep_stack=true
elif [[ -n "${1:-}" ]]; then
  echo "Usage: $0 [--keep]" >&2
  exit 1
fi

cleanup_files() {
  rm -rf -- "${temporary_directory}"
}

show_failure_context() {
  "${compose_command[@]}" ps >&2 || true
  "${compose_command[@]}" logs --tail=200 app >&2 || true
  "${compose_command[@]}" down --volumes >&2 || true
}

trap cleanup_files EXIT
trap show_failure_context ERR

wait_for_health() {
  local attempt
  for attempt in $(seq 1 60); do
    if curl --fail --silent "${base_url}/actuator/health" | jq -e '.status == "UP"' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Application did not become healthy within 120 seconds." >&2
  return 1
}

echo "Validating Compose configuration..."
"${compose_command[@]}" config --quiet

echo "Building and starting SmartDoc..."
"${compose_command[@]}" up --build --detach --wait
wait_for_health

register_payload="$(jq -n \
  --arg username "${smoke_username}" \
  --arg password "${smoke_password}" \
  --arg email "${smoke_username}@example.test" \
  '{username: $username, password: $password, email: $email}')"

token="$(curl --fail --silent --show-error \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "${register_payload}" \
  "${base_url}/api/auth/register" | jq -er '.data.token')"

printf 'SmartDoc Compose smoke fixture: %s\n' "${marker}" > "${temporary_directory}/smoke.txt"

upload_response="$(curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer ${token}" \
  --form "file=@${temporary_directory}/smoke.txt;type=text/plain" \
  "${base_url}/api/documents/upload")"

document_id="$(jq -er '.data.id' <<<"${upload_response}")"
jq -e '.success == true and .data.status == "processing"' <<<"${upload_response}" >/dev/null

echo "Waiting for asynchronous summary and indexing for document ${document_id}..."
pipeline_completed=false
for attempt in $(seq 1 90); do
  status_response="$(curl --fail --silent --show-error \
    --header "Authorization: Bearer ${token}" \
    "${base_url}/api/documents/${document_id}/status")"
  summary_status="$(jq -r '.data.status' <<<"${status_response}")"
  embedding_status="$(jq -r '.data.embeddingStatus' <<<"${status_response}")"

  if [[ "${summary_status}" == "failed" || "${embedding_status}" == "failed" ]]; then
    echo "Document pipeline failed: summary=${summary_status}, embedding=${embedding_status}" >&2
    exit 1
  fi

  if [[ "${summary_status}" == "completed" && "${embedding_status}" == "completed" ]]; then
    pipeline_completed=true
    break
  fi
  sleep 2
done

if [[ "${pipeline_completed}" != "true" ]]; then
  echo "Document pipeline did not complete within 180 seconds." >&2
  exit 1
fi

search_payload="$(jq -n --arg query "${marker}" '{query: $query, topK: 5, similarityThreshold: 0.0}')"
search_response="$(curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer ${token}" \
  --header 'Content-Type: application/json' \
  --data "${search_payload}" \
  "${base_url}/api/search/query")"
jq -e --arg marker "${marker}" '.data | any(.content | contains($marker))' <<<"${search_response}" >/dev/null

ask_payload="$(jq -n --arg question "What evidence marker is present in the uploaded document?" '{question: $question, topK: 5}')"
ask_response="$(curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer ${token}" \
  --header 'Content-Type: application/json' \
  --data "${ask_payload}" \
  "${base_url}/api/search/ask")"
jq -e '.data.answer | length > 0' <<<"${ask_response}" >/dev/null
jq -e --arg marker "${marker}" '.data.answer | contains($marker)' <<<"${ask_response}" >/dev/null
jq -e --arg marker "${marker}" --argjson document_id "${document_id}" \
  '.data.sources | any(.documentId == $document_id and (.content | contains($marker)))' \
  <<<"${ask_response}" >/dev/null

stream_payload="$(jq -n \
  --arg message 'Reply with at least five short sentences confirming that the container SSE stream works.' \
  --arg conversationId 'compose-smoke' \
  '{message: $message, conversationId: $conversationId}')"
curl --fail --silent --show-error --no-buffer --max-time 120 \
  --request POST \
  --header "Authorization: Bearer ${token}" \
  --header 'Accept: text/event-stream' \
  --header 'Content-Type: application/json' \
  --data "${stream_payload}" \
  "${base_url}/api/agent/chat/stream" > "${temporary_directory}/stream.txt"
data_frame_count="$(grep -c '^data:' "${temporary_directory}/stream.txt")"
if (( data_frame_count < 2 )); then
  echo "Expected at least two SSE data frames, received ${data_frame_count}." >&2
  exit 1
fi
if grep -q '^event:error' "${temporary_directory}/stream.txt"; then
  echo "SSE endpoint returned a terminal error event." >&2
  exit 1
fi
if ! grep -q '^event:token' "${temporary_directory}/stream.txt" || \
   ! grep -q '^event:complete' "${temporary_directory}/stream.txt"; then
  echo "SSE endpoint did not return both token and complete events." >&2
  exit 1
fi

echo "Restarting the application container to verify persisted document/vector data..."
"${compose_command[@]}" restart app
wait_for_health

curl --fail --silent --show-error \
  --header "Authorization: Bearer ${token}" \
  "${base_url}/api/documents/${document_id}/status" | jq -e '.data.status == "completed"' >/dev/null

curl --fail --silent --show-error \
  --request POST \
  --header "Authorization: Bearer ${token}" \
  --header 'Content-Type: application/json' \
  --data "${search_payload}" \
  "${base_url}/api/search/query" | \
  jq -e --arg marker "${marker}" '.data | any(.content | contains($marker))' >/dev/null

echo "Compose smoke passed: health, auth, HTTP 202 upload, async processing, grounded Q&A, multi-frame SSE, and restart persistence."
if [[ "${keep_stack}" == "true" ]]; then
  echo "The isolated stack remains running. Stop it with: docker compose --project-name ${compose_project_name} down --volumes"
else
  "${compose_command[@]}" down --volumes
  echo "The isolated smoke stack and its temporary volumes were removed."
fi
