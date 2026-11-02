# 黑马点评（hm-dianping）

基于 Spring Boot + Redis 的本地生活点评类后端项目，覆盖登录认证、商铺缓存、优惠券秒杀、达人探店、关注与 Feed 流、附近商铺、用户签到等典型业务场景。项目主要用于 Redis 高阶应用的学习与实践，最终版已采用 **Redis Lua + Redis Stream + Redisson** 完成高并发秒杀。

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| Java | 1.8 |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3 |
| MySQL | 5.1.47 驱动（`com.mysql.jdbc.Driver`） |
| Redis | 需 5.0 及以上（使用 Stream） |
| spring-data-redis | 2.6.2（手动覆盖，以支持 Stream API） |
| Lettuce | 6.1.6.RELEASE |
| Redisson | 3.13.6（分布式锁） |
| Hutool | 5.7.17 |
| Lombok | 1.18.30 |

## 已实现功能

| 模块 | 说明 | 主要代码 |
| --- | --- | --- |
| 登录认证 | 手机验证码登录，Redis Hash 保存 token，双拦截器刷新/校验，ThreadLocal 保存用户，支持登出 | `UserServiceImpl`、`RefreshTokenInterceptor`、`LoginIntecepter`、`MvcConfig` |
| 缓存穿透 | 缓存空值并设置短期 TTL | `CacheClient#queryWithPassThrough` |
| 缓存击穿 | 互斥锁与逻辑过期两种方案 | `CacheClient#queryWithLogicalExpire`、`SimpleRedisLock` |
| 商铺详情 | 当前启用逻辑过期方案；更新数据库后删除缓存 | `ShopServiceImpl` |
| 商铺类型 | 列表缓存 | `ShopTypeServiceImpl` |
| 优惠券秒杀 | Lua 原子校验库存与一人一单，Redis Stream 异步下单，Redisson 锁，数据库乐观锁扣库存 | `VoucherOrderServiceImpl`、`seckill.lua` |
| 全局唯一 ID | 时间戳 + Redis 按天自增，位运算拼接 | `RedisIdWorker` |
| 达人探店 | 发布笔记、点赞/取消点赞、点赞用户排行、热门笔记 | `BlogServiceImpl` |
| 关注 | 关注/取关（幂等）、是否关注、共同关注（Set 交集） | `FollowServiceImpl` |
| Feed 流 | 推模式写扩散到粉丝 ZSet，按时间戳滚动分页 | `BlogServiceImpl#saveBlog/queryBlogOfFollow` |
| 附近商铺 | Redis GEO 存储坐标，按距离排序分页 | `ShopServiceImpl#queryShopByType` |
| 用户签到 | BitMap + BITFIELD 统计本月连续签到天数 | `UserServiceImpl#sign/signCount` |
| 图片上传 | 本地目录存储，按哈希分目录 | `UploadController` |

未实现/仅骨架：博客评论（`BlogCommentsController`、`BlogCommentsServiceImpl` 为空）、优惠券支付/核销、后台管理。

## 项目结构

```
src/main/java/com/hmdp
├── config        # MVC 拦截器、MyBatis-Plus 分页、Redisson、全局异常
├── controller    # 用户、商铺、商铺类型、优惠券、秒杀订单、博客、上传
├── dto           # Result、LoginFormDTO、UserDTO、ScrollResult
├── entity        # 数据表实体
├── mapper        # MyBatis-Plus Mapper
├── service       # 业务接口
│   └── impl      # 业务实现
└── utils         # Redis 工具、拦截器、ID 生成、用户上下文、常量
src/main/resources
├── application.yaml
├── seckill.lua   # 秒杀原子脚本
├── unlock.lua    # 简易分布式锁释放脚本
└── mapper/VoucherMapper.xml
```

## 快速开始

### 1. 环境准备

- JDK 8、Maven 3.6+
- MySQL 5.7/8.0，创建数据库 `hmdp` 并导入课程提供的建表与数据脚本（本仓库未包含 SQL）
- Redis 5.0+（需要 `XADD`、`XREADGROUP`、`GEOSEARCH` 等命令），推荐 6.x
- 前端工程与静态资源由 Nginx 托管（本仓库仅包含后端）

### 2. 修改配置

| 配置项 | 位置 |
| --- | --- |
| MySQL 地址/账号/密码 | `src/main/resources/application.yaml` |
| Redis 地址/密码 | `application.yaml` 与 `com.hmdp.config.RedissonConfig` |
| 图片上传目录 | `com.hmdp.utils.SystemConstants#IMAGE_UPLOAD_DIR` |
| 服务端口 | `application.yaml`，默认 `8081` |

> 注意：仓库中的数据库/Redis 账号密码、Redis 地址与图片上传目录均已替换为占位符，运行前请填写本地真实值；生产环境建议使用环境变量或外部配置。

### 3. 启动

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

服务默认监听 `http://localhost:8081`。

### 4. 缓存预热（重要）

- **商铺详情**采用逻辑过期方案，未命中缓存时直接返回“商铺不存在”，因此需要先预热：
  - 执行测试类 `HmDianPingApplicationTests#testShopSave` 写入逻辑过期缓存；
  - 或改为调用 `CacheClient#queryWithPassThrough`。
- **附近商铺**依赖 `shop:geo:{typeId}`，执行 `HmDianPingApplicationTests#loadShopData` 导入 GEO 数据。
- **秒杀库存**依赖 `seckill:stock:{voucherId}`，通过 `POST /voucher/seckill` 新增秒杀券时自动写入；手工造数时需同步写入该 key。

## 接口一览

| 方法 | 路径 | 说明 | 是否需要登录 |
| --- | --- | --- | --- |
| POST | `/user/code?phone=` | 发送验证码 | 否 |
| POST | `/user/login` | 登录，返回 token | 否 |
| POST | `/user/logout` | 登出，删除 token | 是 |
| GET | `/user/me` | 当前登录用户 | 是 |
| GET | `/user/info/{id}` | 用户详情 | 是 |
| GET | `/user/{id}` | 查询用户 | 是 |
| POST | `/user/sign` | 签到 | 是 |
| GET | `/user/sign/count` | 本月连续签到天数 | 是 |
| GET | `/shop/{id}` | 商铺详情 | 否 |
| POST | `/shop` | 新增商铺 | 否 |
| PUT | `/shop` | 更新商铺 | 否 |
| GET | `/shop/of/type?typeId=&current=&x=&y=` | 按类型/坐标分页查询 | 否 |
| GET | `/shop/of/name?name=&current=` | 按名称分页查询 | 否 |
| GET | `/shop-type/list` | 商铺类型列表 | 否 |
| POST | `/voucher` | 新增普通券 | 否 |
| POST | `/voucher/seckill` | 新增秒杀券（写入 Redis 库存） | 否 |
| GET | `/voucher/list/{shopId}` | 商铺优惠券列表 | 否 |
| POST | `/voucher-order/seckill/{id}` | 秒杀下单 | 是 |
| POST | `/blog` | 发布探店笔记 | 是 |
| PUT | `/blog/like/{id}` | 点赞/取消点赞 | 是 |
| GET | `/blog/hot` | 热门笔记 | 否 |
| GET | `/blog/{id}` | 笔记详情 | 是 |
| GET | `/blog/of/me` | 我的笔记 | 是 |
| GET | `/blog/of/user?id=&current=` | 指定用户的笔记 | 是 |
| GET | `/blog/likes/{id}` | 点赞用户列表 | 是 |
| GET | `/blog/of/follow?lastId=&offset=` | 关注流滚动分页 | 是 |
| POST | `/upload/blog` | 上传图片 | 否 |
| GET | `/upload/blog/delete?name=` | 删除图片 | 否 |

登录态通过请求头 `authorization` 传递 token。

## Redis Key 约定

| Key | 类型 | 用途 |
| --- | --- | --- |
| `login:code:{phone}` | String | 登录验证码，120 秒 |
| `login:token:{token}` | Hash | 登录用户信息，36000 秒，访问时续期 |
| `cache:shop:{id}` | String | 商铺详情缓存（当前存逻辑过期结构） |
| `cache:shoptype` | String | 商铺类型列表缓存（无 TTL） |
| `seckill:stock:{voucherId}` | String | 秒杀库存 |
| `seckill:order:{voucherId}` | Set | 已下单用户集合，一人一单 |
| `stream.orders` | Stream | 秒杀订单消息队列（消费组 `g1`，消费者 `c1`） |
| `blog:liked:{blogId}` | ZSet | 点赞用户，score 为点赞时间 |
| `feed:{userId}` | ZSet | 粉丝收件箱，score 为笔记发布时间 |
| `follows:{userId}` | Set | 当前用户关注的人 |
| `shop:geo:{typeId}` | GEO | 同类型商铺坐标 |
| `sign:{userId}:{yyyyMM}` | BitMap | 用户月度签到 |
| `icr:{biz}:{yyyy:MM:dd}` | String | 全局 ID 自增序列 |
| `lock:shop:{id}` | String | 商铺缓存重建互斥锁 |

## 核心实现说明

### 登录与鉴权

1. 验证码写入 Redis，有效期 2 分钟；登录成功后生成 UUID token。
2. 用户信息以 Hash 存入 `login:token:{token}`，有效期 10 小时。
3. `RefreshTokenInterceptor`（order 0）解析 token、写入 `UserHolder` 并续期；`LoginIntecepter`（order 1）对未登录请求返回 401。
4. 请求结束在 `afterCompletion` 中清理 ThreadLocal，避免线程复用导致用户串号。

### 秒杀下单

1. `seckill.lua` 原子完成：库存判断、一人一单判断、扣库存、`SADD` 下单用户、`XADD` 写入 `stream.orders`。
2. 接口只返回订单 ID，实际落库由 `VoucherOrderHandler` 异步消费。
3. 消费者使用消费组 `g1`/`c1`，先读新消息，异常时重试 Pending List，成功后 `XACK`。
4. 消费者线程通过 `proxy.createVoucherOrder(...)` 调用事务方法（`@EnableAspectJAutoProxy(exposeProxy = true)`），事务注解才会生效。
5. 落库前再次校验一人一单，并用 `stock = stock - 1 AND stock > 0` 乐观锁扣库存。

### Feed 流

- 发布笔记后查询粉丝列表，将 `blogId` 写入每个粉丝的 `feed:{userId}` ZSet，score 为发布时间戳（推模式）。
- 滚动分页使用 `reverseRangeByScoreWithScores(key, 0, lastId, offset, 2)`，处理同一时间戳的偏移量，避免重复/漏读。

### 附近商铺

- 数据按 `typeId` 写入 `shop:geo:{typeId}`。
- 查询时按坐标 `GEOSEARCH` 5 公里范围，带距离并 `limit(end)`，内存中 `skip(from)` 后按距离顺序回查数据库，并补齐 `distance` 字段。

## 代码检查结论

本次检查执行了 `mvn -o compile`，编译通过（无错误）。逐模块静态审查发现的问题如下，按严重程度排序：

| 级别 | 位置 | 问题 | 建议 |
| --- | --- | --- | --- |
| 高 | `utils/CacheClient.java#queryWithLogicalExpire` | 互斥锁 key 与缓存 key 完全相同（`keyPrefix + id`），缓存命中时 `setIfAbsent` 必然失败，导致逻辑过期缓存**永远不会重建**，数据一直陈旧 | 使用独立的锁前缀，如 `String lockKey = "lock:" + key;`（或在调用处传入 `LOCK_SHOP_KEY`） |
| 中 | `service/impl/ShopServiceImpl.java#queryById` | 逻辑过期策略依赖预热；`update` 删除缓存后，详情接口会持续返回“商铺不存在” | 未命中时回源数据库并重建缓存（cache-aside），或改为穿透方案 |
| 中 | `controller/UploadController.java#deleteBlogImg` | `name` 参数未校验，存在 `../` 路径穿越删除风险 | 校验文件名不含 `/`、`\`、`..`，并确保最终路径位于上传目录内 |
| 已处理 | `application.yaml`、`config/RedissonConfig.java`、`utils/SystemConstants.java` | 数据库/Redis 账号密码、Redis 地址与个人目录已脱敏为占位符 | 本地运行前填写真实值，正式环境使用环境变量或配置中心 |
| 中 | `service/impl/BlogServiceImpl.java#queryBlogLikes` | `range(key, 0, 4)` 取的是**最早**点赞的 5 人，不是最新 | 如需最新点赞，改用 `reverseRange(key, 0, 4)` |
| 低 | `service/impl/BlogServiceImpl.java#queryBlogUser` | 作者被删除时 `user` 为 null，会 NPE | 增加判空与默认值 |
| 低 | `service/impl/BlogServiceImpl.java#queryBlogOfFollow` | 无数据时返回空 List 而不是 `ScrollResult` | 统一返回结构，避免前端取字段异常 |
| 低 | `resources/seckill.lua`、`VoucherOrderServiceImpl` | 未校验优惠券生效/失效时间；库存 key 缺失时直接按库存不足处理 | 增加时间校验，或在新增秒杀券时确保库存已初始化 |
| 低 | `service/impl/FollowServiceImpl.java#followCommons` | 共同关注完全依赖 `follows:` Set，历史关注关系未初始化时结果为空 | 启动时从 `tb_follow` 初始化，或退化为数据库交集查询 |
| 低 | `service/impl/BlogServiceImpl.java#likeBlog` | 数据库点赞数与 Redis ZSet 双写非原子，极端情况下不一致 | 可接受，备注即可；要求强一致时改为消息补偿 |
| 低 | `service/impl/VoucherOrderServiceImpl.java` | Pending List 仅在主循环抛异常时处理，锁竞争失败的消息会延迟重试；单线程池未在关闭时 `shutdown` | 定时扫描 Pending List；注册销毁钩子 |
| 低 | `service/impl/ShopTypeServiceImpl.java` | 类型缓存未设置 TTL，数据库变更后不会失效 | 增加 TTL 或在管理端更新时删除缓存 |
| 低 | `service/impl/UserServiceImpl.java#login` | 验证码校验成功后未删除，TTL 内可重放；验证码明文打印日志 | 登录成功后删除 code；生产环境关闭验证码日志并增加发送频率限制 |
| 低 | `utils/RedisIdWorker.java` | `BEGIN_TIMESTAMP = 1790263320L` 约等于 2026-09-24，若本意是项目起始日（如 2022-01-01）则属于笔误 | 确认后改为固定起始时间戳 |
| 低 | `utils/SimpleRedisLock.java`、`utils/ILock.java`、`resources/unlock.lua` | 当前秒杀已改用 Redisson，上述代码未被使用 | 作为学习记录保留，或在最终版清理 |
| 低 | `controller/ShopController.java` | 注入了未使用的 `StringRedisTemplate` | 删除无用依赖 |

说明：以上为静态审查结论，未修改业务代码；需要修复时可按“建议”列逐项处理。

## 许可与说明

本项目基于黑马程序员《黑马点评》课程资料学习实现，仅用于学习与技术验证。
