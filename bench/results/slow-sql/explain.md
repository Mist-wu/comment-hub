## 修复前

```sql
SELECT id, like_count FROM comment WHERE video_id = 1 AND status <> 3 ORDER BY like_count DESC, id DESC LIMIT 1000;
```
```
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
| id | select_type | table   | partitions | type | possible_keys     | key               | key_len | ref   | rows    | filtered | Extra                       |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created | idx_video_created | 8       | const | 3412434 |    90.00 | Using where; Using filesort |
+----+-------------+---------+------------+------+-------------------+-------------------+---------+-------+---------+----------+-----------------------------+
-> Limit: 1000 row(s)  (cost=354879 rows=1000) (actual time=1309..1309 rows=1000 loops=1)
    -> Sort: `comment`.like_count DESC, `comment`.id DESC, limit input to 1000 row(s) per chunk  (cost=354879 rows=3.41e+6) (actual time=1309..1309 rows=1000 loops=1)
        -> Filter: (`comment`.`status` <> 3)  (cost=354879 rows=3.41e+6) (actual time=0.738..1229 rows=1.57e+6 loops=1)
            -> Index lookup on comment using idx_video_created (video_id=1)  (cost=354879 rows=3.41e+6) (actual time=0.418..1167 rows=1.62e+6 loops=1)

```

## 修复后：ALTER TABLE comment ADD KEY idx_video_like (video_id, like_count, id, status)，耗时 9s

```
+----+-------------+---------+------------+------+----------------------------------+----------------+---------+-------+---------+----------+-----------------------------------------------+
| id | select_type | table   | partitions | type | possible_keys                    | key            | key_len | ref   | rows    | filtered | Extra                                         |
+----+-------------+---------+------------+------+----------------------------------+----------------+---------+-------+---------+----------+-----------------------------------------------+
|  1 | SIMPLE      | comment | NULL       | ref  | idx_video_created,idx_video_like | idx_video_like | 8       | const | 3438336 |    90.00 | Using where; Backward index scan; Using index |
+----+-------------+---------+------------+------+----------------------------------+----------------+---------+-------+---------+----------+-----------------------------------------------+
-> Limit: 1000 row(s)  (cost=321601 rows=1000) (actual time=0.0384..0.245 rows=1000 loops=1)
    -> Filter: (`comment`.`status` <> 3)  (cost=321601 rows=3.09e+6) (actual time=0.0381..0.212 rows=1000 loops=1)
        -> Covering index lookup on comment using idx_video_like (video_id=1) (reverse)  (cost=321601 rows=3.44e+6) (actual time=0.0343..0.17 rows=1036 loops=1)

```
+-------------------+---------+
| index_name        | size_mb |
+-------------------+---------+
| PRIMARY           |     995 |
| idx_video_created |     315 |
| idx_video_like    |     314 |
+-------------------+---------+
