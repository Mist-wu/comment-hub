#!/usr/bin/env bash
# 把 eval/moderation-gold.tsv（来源、标注、评论内容）导入 moderation_gold，供 /admin/eval/moderation 使用。
# 会清空 moderation_gold 原有数据（datagen 导入的是未标注的抽样）。
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose cp eval/moderation-gold.tsv mysql:/var/lib/mysql-files/moderation-gold.tsv
docker compose exec -T mysql mysql --default-character-set=utf8mb4 -uroot -proot comment_hub -e "
TRUNCATE TABLE moderation_gold;
LOAD DATA INFILE '/var/lib/mysql-files/moderation-gold.tsv' INTO TABLE moderation_gold CHARACTER SET utf8mb4
  FIELDS TERMINATED BY '\t' LINES TERMINATED BY '\n' (source, human_label, content);
SELECT source, human_label, COUNT(*) FROM moderation_gold GROUP BY source, human_label;"
