# 关键 SQL 的执行计划

数据：视频 1，1514580 条评论；comment 表共 9670027 行（估算）。
每条 SQL 先跑一次预热 buffer pool，再取 EXPLAIN ANALYZE。生成时间：2026-11-19 00:19:16。

## LIMIT offset，第 1 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 0, 20;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                            |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |    90.00 | Using where; Backward index scan |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
-> Limit: 20 row(s)  (cost=324532 rows=20) (actual time=0.347..0.35 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=324532 rows=2.77e+6) (actual time=0.339..0.342 rows=20 loops=1)
        -> Index lookup on comment using idx_video_created (video_id=1) (reverse)  (cost=324532 rows=3.08e+6) (actual time=0.335..0.337 rows=20 loops=1)

```

## 游标，第 1 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 20;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                            |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |    90.00 | Using where; Backward index scan |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
-> Limit: 20 row(s)  (cost=324532 rows=20) (actual time=0.0576..0.0607 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=324532 rows=2.77e+6) (actual time=0.0575..0.06 rows=20 loops=1)
        -> Index lookup on comment using idx_video_created (video_id=1) (reverse)  (cost=324532 rows=3.08e+6) (actual time=0.0565..0.0582 rows=20 loops=1)

```

## LIMIT offset，第 100 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 1980, 20;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                            |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |    90.00 | Using where; Backward index scan |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
-> Limit/Offset: 20/1980 row(s)  (cost=324532 rows=20) (actual time=1.4..1.41 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=324532 rows=2.77e+6) (actual time=0.0588..1.37 rows=2000 loops=1)
        -> Index lookup on comment using idx_video_created (video_id=1) (reverse)  (cost=324532 rows=3.08e+6) (actual time=0.0578..1.3 rows=2065 loops=1)

```

## 游标，第 100 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 AND (created_at < '2026-10-29 21:05:00.067000' OR (created_at = '2026-10-29 21:05:00.067000' AND id < 9986498)) ORDER BY created_at DESC, id DESC LIMIT 20;
```
```
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
| id | select_type | table   | partitions | type  | possible_keys             | key               | key_len | ref  | rows    | filtered | Extra                                                   |
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
|  1 | SIMPLE      | comment | NULL       | range | PRIMARY,idx_video_created | idx_video_created | 23      | NULL | 3195129 |    90.00 | Using index condition; Using where; Backward index scan |
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
-> Limit: 20 row(s)  (cost=982926 rows=20) (actual time=0.0701..0.0731 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=982926 rows=2.88e+6) (actual time=0.0698..0.0722 rows=20 loops=1)
        -> Index range scan on comment using idx_video_created over (video_id = 1 AND created_at < '2026-10-29 21:05:00.067') OR (video_id = 1 AND created_at = '2026-10-29 21:05:00.067' AND id < 9986498) (reverse), with index condition: ((`comment`.video_id = 1) and ((`comment`.created_at < TIMESTAMP'2026-10-29 21:05:00.067') or ((`comment`.created_at = TIMESTAMP'2026-10-29 21:05:00.067') and (`comment`.id < 9986498))))  (cost=982926 rows=3.2e+6) (actual time=0.0692..0.0705 rows=21 loops=1)

```

## LIMIT offset，第 1000 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY created_at DESC, id DESC LIMIT 19980, 20;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                            |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |    90.00 | Using where; Backward index scan |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+----------------------------------+
-> Limit/Offset: 20/19980 row(s)  (cost=324532 rows=20) (actual time=13.7..13.7 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=324532 rows=2.77e+6) (actual time=0.0572..13.4 rows=20000 loops=1)
        -> Index lookup on comment using idx_video_created (video_id=1) (reverse)  (cost=324532 rows=3.08e+6) (actual time=0.0562..12.6 rows=20614 loops=1)

```

## 游标，第 1000 页

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 AND (created_at < '2026-10-28 18:34:09.667000' OR (created_at = '2026-10-28 18:34:09.667000' AND id < 9863748)) ORDER BY created_at DESC, id DESC LIMIT 20;
```
```
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
| id | select_type | table   | partitions | type  | possible_keys             | key               | key_len | ref  | rows    | filtered | Extra                                                   |
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
|  1 | SIMPLE      | comment | NULL       | range | PRIMARY,idx_video_created | idx_video_created | 23      | NULL | 2994137 |    90.00 | Using index condition; Using where; Backward index scan |
+----+-------------+---------+------------+-------+---------------------------+-------------------+---------+------+---------+----------+---------------------------------------------------------+
-> Limit: 20 row(s)  (cost=982926 rows=20) (actual time=0.0665..0.0693 rows=20 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=982926 rows=2.69e+6) (actual time=0.0663..0.0685 rows=20 loops=1)
        -> Index range scan on comment using idx_video_created over (video_id = 1 AND created_at < '2026-10-28 18:34:09.667') OR (video_id = 1 AND created_at = '2026-10-28 18:34:09.667' AND id < 9863748) (reverse), with index condition: ((`comment`.video_id = 1) and ((`comment`.created_at < TIMESTAMP'2026-10-28 18:34:09.667') or ((`comment`.created_at = TIMESTAMP'2026-10-28 18:34:09.667') and (`comment`.id < 9863748))))  (cost=982926 rows=2.99e+6) (actual time=0.0657..0.0671 rows=20 loops=1)

```

## SELECT id, video_id, user_id, content, l…

```sql
SELECT id, video_id, user_id, content, like_count, status, created_at FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY like_count DESC, id DESC LIMIT 0, 20;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                       |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |    90.00 | Using where; Using filesort |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
-> Limit: 20 row(s)  (cost=324532 rows=20) (actual time=1013..1013 rows=20 loops=1)
    -> Sort: `comment`.like_count DESC, `comment`.id DESC, limit input to 20 row(s) per chunk  (cost=324532 rows=3.08e+6) (actual time=1013..1013 rows=20 loops=1)
        -> Filter: (`comment`.`status` <> 3)  (cost=324532 rows=3.08e+6) (actual time=0.0981..932 rows=1.47e+6 loops=1)
            -> Index lookup on comment using idx_video_created (video_id=1)  (cost=324532 rows=3.08e+6) (actual time=0.0958..875 rows=1.51e+6 loops=1)

```

## SELECT COUNT(*) FROM comment WHERE video…

```sql
SELECT COUNT(*) FROM comment WHERE video_id = 1;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra       |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3075116 |   100.00 | Using index |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-------------+
-> Aggregate: count(0)  (cost=626628 rows=1) (actual time=117..117 rows=1 loops=1)
    -> Covering index lookup on comment using idx_video_created (video_id=1)  (cost=319117 rows=3.08e+6) (actual time=0.279..88.7 rows=1.51e+6 loops=1)

```
