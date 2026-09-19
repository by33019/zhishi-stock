#!/usr/bin/env python3
"""把 Navicat 导出的旧库全量 dump 裁剪为可入库的精简基线样本。

背景
----
`sql/stock_db.sql` 是原项目（MySQL 5.6）的 Navicat 全量导出，共 11 张表、
约 15.1 万行数据。其中 `stock_rt_info` 单表 145,382 行（逐分钟 × 全市场个股快照）
占了整个文件的 97%，24 MB 的体积对「已有数据库升级路径演练」毫无必要。

本脚本保留**全部 11 张表的 DDL**，只对三张体量最大的行情表按行数采样，
其余小表（尤其是 RBAC 相关的 sys_* 表）完整保留，因为升级路径校验依赖它们。

用法
----
    python sql/tools/slim_legacy_dump.py <输入.sql> <输出.sql>

设计约束
--------
- 只依赖 Python 标准库。
- 逐行处理，不解析 SQL 语法：原导出中每条 INSERT 都严格占一行，已核实。
- 除 INSERT 行外的一切内容（头部注释、SET、DROP、CREATE TABLE、段落注释、尾部 SET）原样保留。
- 幂等：对已裁剪过的文件再跑一次不会继续缩减（因为行数已低于上限）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# 单表行数上限。未列出的表全部保留。
ROW_LIMITS: dict[str, int] = {
    "stock_rt_info": 300,
    "stock_market_index_info": 200,
    "stock_block_rt_info": 60,
}

INSERT_RE = re.compile(r"^INSERT INTO `(?P<table>[A-Za-z0-9_]+)` VALUES ", re.IGNORECASE)
CREATE_RE = re.compile(r"^CREATE TABLE `(?P<table>[A-Za-z0-9_]+)`", re.IGNORECASE)


def slim(source: Path, target: Path) -> dict[str, tuple[int, int]]:
    """返回 {表名: (保留行数, 原始行数)}。"""
    kept: dict[str, int] = {}
    total: dict[str, int] = {}
    current = "<header>"

    out_lines: list[str] = []
    for line in source.read_text(encoding="utf-8").splitlines():
        create = CREATE_RE.match(line)
        if create:
            current = create.group("table")

        insert = INSERT_RE.match(line)
        if not insert:
            out_lines.append(line)
            continue

        table = insert.group("table")
        total[table] = total.get(table, 0) + 1
        limit = ROW_LIMITS.get(table)
        if limit is not None and total[table] > limit:
            continue
        kept[table] = kept.get(table, 0) + 1
        out_lines.append(line)

    target.write_text("\n".join(out_lines) + "\n", encoding="utf-8", newline="\n")
    return {table: (kept.get(table, 0), count) for table, count in total.items()}


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    source, target = Path(sys.argv[1]), Path(sys.argv[2])
    if not source.is_file():
        print(f"输入文件不存在：{source}", file=sys.stderr)
        return 1

    stats = slim(source, target)

    before = source.stat().st_size
    after = target.stat().st_size
    print(f"{source} -> {target}")
    print(f"体积：{before:,} -> {after:,} 字节（{after / before:.1%}）")
    for table in sorted(stats):
        kept, total = stats[table]
        mark = "采样" if kept != total else "完整"
        print(f"  {table:<32} {kept:>7,} / {total:>7,} 行  [{mark}]")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
