# -*- coding: utf-8 -*-
"""
验证「初次建表」是否真的能从零装起来。

做四件事：
  1 用 db/schema.sql + db/data.sql 在**临时库**里建一遍（不碰 food_ledger）
  2 逐表逐列对比临时库与实际库的差异（列名/类型/可空/默认值/索引）
  3 用临时库启动一次应用，验证登录、模板、台账都能用
  4 清理临时库

用法：python qa/verify-fresh-install.py
"""

import json
import os
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIVE = "food_ledger"
CHECK = "food_ledger_check"
PORT = 8082
MYSQL = "mysql"


def run_sql(sql, database=None):
    """把 SQL 通过 stdin 交给 mysql 客户端（文件是 UTF-8，必须指定字符集）。"""
    cmd = [MYSQL, "-u", "root", "--default-character-set=utf8mb4"]
    if database:
        cmd.append(database)
    p = subprocess.run(cmd, input=sql.encode("utf-8"),
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "ignore"))
    return p.stdout.decode("utf-8", "ignore")


def query(sql, database=None):
    out = run_sql("SET NAMES utf8mb4;\n" + sql, database)
    rows = []
    for line in out.strip().splitlines()[1:]:
        rows.append(line.split("\t"))
    return rows


def columns(schema, table):
    sql = ("SELECT column_name, column_type, is_nullable, IFNULL(column_default,'<null>') "
           "FROM information_schema.columns WHERE table_schema='%s' AND table_name='%s' "
           "ORDER BY ordinal_position;" % (schema, table))
    return query(sql)


def tables(schema):
    rows = query("SELECT table_name FROM information_schema.tables "
                 "WHERE table_schema='%s' ORDER BY table_name;" % schema)
    return [r[0] for r in rows]


def indexes(schema, table):
    sql = ("SELECT DISTINCT index_name FROM information_schema.statistics "
           "WHERE table_schema='%s' AND table_name='%s' ORDER BY index_name;" % (schema, table))
    return sorted(r[0] for r in query(sql))


def kill_port(port):
    """按端口杀进程：只按镜像名杀 java 会漏掉 spring-boot 的 fork 进程，
    上一次验证遗留的实例会占着端口，把这次的结果污染成「登录失败」。"""
    try:
        out = subprocess.run(["netstat", "-ano"], capture_output=True, text=True).stdout
    except Exception:
        return
    pids = set()
    for line in out.splitlines():
        if (":%d " % port) in line and "LISTENING" in line.upper():
            pids.add(line.split()[-1])
    for pid in pids:
        subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)


def main():
    os.environ.setdefault("MYSQL_PWD", os.environ.get("MYSQL_PWD", ""))

    # ---------- 1 在临时库里从零建 ----------
    print("=" * 78)
    print("[1] 用 schema.sql + data.sql 在临时库 %s 里从零建" % CHECK)
    run_sql("DROP DATABASE IF EXISTS %s; CREATE DATABASE %s "
            "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" % (CHECK, CHECK))

    schema_sql = open(os.path.join(ROOT, "src/main/resources/db/schema.sql"),
                      encoding="utf-8").read()
    # 把库名换成临时库；跑之前先自检，绝不能让这份脚本落到 food_ledger 上
    check_sql = schema_sql.replace(LIVE, CHECK)
    assert ("USE %s;" % CHECK) in check_sql, "库名替换失败"
    assert ("USE %s;" % LIVE) not in check_sql, "临时脚本里仍指向生产库，已中止"
    assert "DROP DATABASE" not in check_sql, "建表脚本不应包含 DROP DATABASE"
    run_sql(check_sql)
    print("    schema.sql 执行成功")

    data_sql = open(os.path.join(ROOT, "src/main/resources/db/data.sql"),
                    encoding="utf-8").read()
    # 双重保险：种子文件里若还有人加回 USE（曾经有一句 USE food_ledger，
    # 结果把演练脚本切回了生产库），这里直接剥掉，库由脚本显式指定。
    data_lines = [ln for ln in data_sql.splitlines()
                  if not ln.strip().lower().startswith("use ")]
    assert all("USE " + LIVE not in ln.upper() for ln in data_lines), "种子文件仍指向生产库"
    run_sql("USE %s;\n" % CHECK + "\n".join(data_lines))
    print("    data.sql 执行成功")

    # ---------- 2 与实库对比 ----------
    print("\n[2] 临时库 vs 实际库 结构对比")
    live_tables = tables(LIVE)
    check_tables = tables(CHECK)
    print("    实际库 %d 张表，临时库 %d 张表" % (len(live_tables), len(check_tables)))
    only_live = sorted(set(live_tables) - set(check_tables))
    only_check = sorted(set(check_tables) - set(live_tables))
    print("    只在实库: %s" % (only_live or "无"))
    print("    只在临时库: %s" % (only_check or "无"))

    drift = 0
    for t in sorted(set(live_tables) & set(check_tables)):
        lcols, ccols = columns(LIVE, t), columns(CHECK, t)
        lnames = [c[0] for c in lcols]
        cnames = [c[0] for c in ccols]
        miss = [c for c in lnames if c not in cnames]
        extra = [c for c in cnames if c not in lnames]
        # 同名列比类型/可空/默认
        lmap = {c[0]: c[1:] for c in lcols}
        cmap = {c[0]: c[1:] for c in ccols}
        typediff = [(n, lmap[n], cmap[n]) for n in lnames
                    if n in cmap and lmap[n] != cmap[n]]
        lidx, cidx = indexes(LIVE, t), indexes(CHECK, t)
        idxdiff = sorted(set(lidx) ^ set(cidx))
        if miss or extra or typediff or idxdiff:
            drift += 1
            print("    ✗ %-20s 缺列=%s 多列=%s 类型差=%s 索引差=%s"
                  % (t, miss or "-", extra or "-",
                     [x[0] for x in typediff] or "-", idxdiff or "-"))
        else:
            print("    ✓ %-20s %d 列，索引 %d 个，完全一致"
                  % (t, len(lnames), len(cidx)))

    print("    → 结论：%s" % ("结构与实库一致，可安全用于新装"
                            if drift == 0 and not only_live and not only_check
                            else "存在 %d 张表的结构差异（见上）" % drift))

    # ---------- 3 用临时库启动应用 ----------
    print("\n[3] 用临时库启动应用（端口 %d），验证能否直接用" % PORT)
    kill_port(PORT)   # 清掉上一次验证可能遗留的实例
    mvn = shutil.which("mvn") or "mvn"
    log = open(os.path.join(ROOT, "target", "fresh-install.log"), "wb")
    proc = subprocess.Popen(
        [mvn, "-B", "spring-boot:run",
         "-Dspring-boot.run.arguments="
         "--server.port=%d --spring.datasource.url=jdbc:mysql://localhost:3306/%s"
         "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
         "&allowPublicKeyRetrieval=true&useSSL=false" % (PORT, CHECK)],
        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)

    base = "http://localhost:%d" % PORT
    ready = False
    for _ in range(90):
        try:
            urllib.request.urlopen(base + "/api/auth/me", timeout=3)
            ready = True
            break
        except urllib.error.HTTPError:
            ready = True
            break
        except Exception:
            time.sleep(2)

    ok = True
    if not ready:
        print("    ✗ 启动失败，见 target/fresh-install.log")
        ok = False
    else:
        import http.cookiejar
        op = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

        def call(path, body=None):
            data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
            req = urllib.request.Request(base + path, data=data,
                                         method="POST" if body else "GET",
                                         headers={"Content-Type": "application/json"})
            with op.open(req, timeout=60) as r:
                return json.loads(r.read().decode())

        login = call("/api/auth/login", {"username": "caigou", "password": "123456"})
        print("    演示账号能登录（StaffSeeder 自动建）: %s"
              % ("是" if login["code"] == 0 else "否 ← " + login["message"]))
        tpl = call("/api/templates/default").get("data") or {}
        print("    默认模板已就绪: %s（%d 个字段）"
              % (tpl.get("name"), len(tpl.get("fields") or [])))
        cats = call("/api/categories").get("data") or []
        print("    品类主数据: %d 个" % len(cats))
        page = call("/api/ledger/page?page=1&size=5").get("data") or {}
        print("    台账列表可用: total=%s（空库应为 0）" % page.get("total"))
        ok = (login["code"] == 0 and len(tpl.get("fields") or []) > 0 and len(cats) > 0)

    proc.terminate()
    try:
        proc.wait(timeout=25)
    except subprocess.TimeoutExpired:
        proc.kill()
    log.close()

    # ---------- 4 清理 ----------
    # 必须按端口杀：spring-boot:run 会 fork 一个子 java 进程，
    # 只 terminate 父进程（mvn）留下的子进程会继续占着端口，
    # 下一次验证就连到了上一次的实例，结果全是假的。
    kill_port(PORT)
    run_sql("DROP DATABASE IF EXISTS %s;" % CHECK)
    print("\n[4] 已清理临时库 %s" % CHECK)
    print("\n" + "=" * 78)
    print("初次建表结论：%s" % ("可以用" if ok and drift == 0 else "需要处理上面的问题"))
    return 0 if ok and drift == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
