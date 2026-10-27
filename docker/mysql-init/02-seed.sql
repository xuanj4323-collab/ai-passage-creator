-- ============================================================
-- 初始化种子数据：三个内置测试账号
-- 密码统一为 12345678（MD5 加盐加密，盐值 mypj）
--   admin / 12345678   管理员
--   user  / 12345678   普通用户
--   test  / 12345678   测试账号
--
-- 说明：表结构由 01-schema.sql 从开发库导出，这里只补最小可用数据。
-- 用 ON DUPLICATE KEY 保证重复执行不会报错（容器重建时更稳）。
-- ============================================================

-- 必须显式声明连接字符集。
-- MySQL 容器默认没有 UTF-8 locale，客户端会退化成 latin1，
-- 本文件里的中文会被静默替换成 ?（实测表注释会变成 ????）。
-- 配合 docker-compose.yml 里 mysql 服务的 LANG=C.UTF-8 一起使用。
SET NAMES utf8mb4;

USE `ai_passage_creator`;

INSERT INTO `user`
    (id, userAccount, userPassword, userName, userAvatar, userProfile, userRole)
VALUES
    (1, 'admin', 'f5537b06c869cc8601d0538a818f8e29', '管理员',
     'https://www.codefather.cn/logo.png', '系统管理员', 'admin'),
    (2, 'user', 'f5537b06c869cc8601d0538a818f8e29', '普通用户',
     'https://www.codefather.cn/logo.png', '我是一个普通用户', 'user'),
    (3, 'test', 'f5537b06c869cc8601d0538a818f8e29', '测试账号',
     'https://www.codefather.cn/logo.png', '这是一个测试账号', 'user')
ON DUPLICATE KEY UPDATE userAccount = userAccount;
