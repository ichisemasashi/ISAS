#!/usr/bin/env python3
"""別 Postgres で M4 スパイクを実行するランナー（psql 不要・psycopg[binary] 使用）。

使い方:
  export SPIKE_DSN="postgresql://user:pass@host:5432/dbname"
  python3 spikes/run_psycopg.py all        # または S1 / S2 / S4

psql メタコマンドは最小限だけ解釈する:
  \\set NAME 'val'  → プレースホルダ（:'NAME' / :NAME を置換）
  \\echo ...        → 標準出力へ
  それ以外の行はそのまま SQL として送る（SET ROLE / DO / EXPLAIN 等は素の SQL）。
NOTICE（RAISE NOTICE の PASS/FAIL）はハンドラで表示する。
"""
import os, re, sys, pathlib

try:
    import psycopg
except ImportError:
    sys.exit("psycopg が必要です: python3 -m pip install --user 'psycopg[binary]'")

HERE = pathlib.Path(__file__).parent
DSN = os.environ.get("SPIKE_DSN")
if not DSN:
    sys.exit("環境変数 SPIKE_DSN を設定してください（例: postgresql://user:pass@host:5432/db）")

FILES = {
    "S1": "S1_partition_rls_unique.sql",
    "S2": "S2_spatial_rls.sql",
    "S4": "S4_rls_scale.sql",
}

def split_statements(sql_text, variables):
    """psql メタコマンドを処理し、SQL 文のリストへ分割する。"""
    # 変数置換
    def apply_vars(s):
        for k, v in variables.items():
            s = s.replace(f":'{k}'", f"'{v}'").replace(f":{k}", v)
        return s

    out_lines, echoes = [], []
    for raw in sql_text.splitlines():
        line = raw
        m = re.match(r"\s*\\set\s+(\w+)\s+'([^']*)'", line)
        if m:
            variables[m.group(1)] = m.group(2)
            continue
        if re.match(r"\s*\\echo", line):
            # \echo は文の区切りにマーカーを残す
            txt = line.strip()[len("\\echo"):].strip().strip("'")
            out_lines.append(f"-- __ECHO__ {txt}")
            continue
        if re.match(r"\s*\\", line):
            continue  # 他のメタコマンドは無視
        out_lines.append(apply_vars(line))

    body = "\n".join(out_lines)
    # $$ ... $$ を保護しつつ ; で分割
    stmts, buf, dollar = [], [], False
    for line in body.splitlines():
        if line.startswith("-- __ECHO__"):
            if "".join(buf).strip():
                stmts.append("".join(buf)); buf = []
            stmts.append(("ECHO", line[len("-- __ECHO__"):].strip()))
            continue
        buf.append(line + "\n")
        dollar ^= line.count("$$") % 2 == 1
        if not dollar and line.rstrip().endswith(";"):
            stmts.append("".join(buf)); buf = []
    if "".join(buf).strip():
        stmts.append("".join(buf))
    return stmts

def run_file(conn, path):
    print(f"\n{'#'*56}\n# 実行: {path.name}\n{'#'*56}")
    variables = {}
    stmts = split_statements(path.read_text(), variables)
    fails = 0
    for st in stmts:
        if isinstance(st, tuple) and st[0] == "ECHO":
            print(st[1]); continue
        s = st.strip()
        if not s or s.startswith("--"):
            continue
        with conn.cursor() as cur:
            try:
                cur.execute(s)
                if cur.description and s.lower().startswith(("explain", "select")):
                    for row in cur.fetchall():
                        print("   ", *row)
            except Exception as e:
                print(f"!! ERROR: {e}\n   at: {s[:120].splitlines()[0] if s else ''}")
                fails += 1
        conn.commit()
    return fails

def main():
    target = sys.argv[1] if len(sys.argv) > 1 else "all"
    targets = list(FILES) if target == "all" else [target]

    with psycopg.connect(DSN, autocommit=False) as conn:
        # NOTICE(PASS/FAIL) を表示
        conn.add_notice_handler(lambda diag: print("   ", (diag.message_primary or "").strip()))
        print("== 共通セットアップ 00_common.sql ==")
        run_file(conn, HERE / "00_common.sql")
        total = 0
        for t in targets:
            # 各スパイクは独立スキーマで（public を作り直す）
            with conn.cursor() as cur:
                cur.execute("DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;")
            conn.commit()
            run_file(conn, HERE / "00_common.sql")
            total += run_file(conn, HERE / FILES[t])
        print(f"\n== 完了。ERROR 件数 = {total}（0 かつ 各 PASS 表示なら合格）==")

if __name__ == "__main__":
    main()
