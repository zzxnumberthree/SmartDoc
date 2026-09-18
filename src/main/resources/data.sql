-- 初始化管理员用户 (密码: 123456 的 BCrypt 哈希)
-- 使用 username 唯一约束触发 ON DUPLICATE KEY UPDATE，确保密码始终为 BCrypt 格式
-- 注意：如果 admin_user_1 不存在则创建，如果已存在则更新密码和角色
INSERT INTO `users` (`username`, `password`, `email`, `role`, `is_deleted`, `updated_at`, `created_at`)
VALUES ('admin_user_1',
        '$2a$10$TcnxK1Us.3LpESXDr7JxS.zwRodkORIt2IpE6pG71AbASnDsb9DJS',
        'admin@example.com', 'ADMIN', 0, current_timestamp, current_timestamp)
ON DUPLICATE KEY UPDATE
    password = '$2a$10$TcnxK1Us.3LpESXDr7JxS.zwRodkORIt2IpE6pG71AbASnDsb9DJS',
    role = 'ADMIN';