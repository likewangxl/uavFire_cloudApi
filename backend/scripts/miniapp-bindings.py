"""Server-side binding review. Credentials come only from MINIAPP_DB_* environment variables.

Examples: python miniapp-bindings.py list
          python miniapp-bindings.py approve ID --user-id USER --workspace-id WS --operator ADMIN --reason REASON
No HTTP admin bypass, passwords, OpenIDs or tokens are printed.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import uuid


def review(conn, action, identity_id, user_id, workspace_id, operator, reason):
    if not operator or not reason or len(operator) > 128 or len(reason) > 255:
        raise ValueError("operator 和 reason 必须填写，长度分别不超过 128 / 255")
    with conn.cursor() as cursor:
        cursor.execute("SELECT binding_status,requested_username FROM miniapp_identity WHERE identity_id=%s FOR UPDATE", (identity_id,))
        identity = cursor.fetchone()
        allowed = {"approve": "PENDING", "reject": "PENDING", "disable": "APPROVED"}
        if not identity or identity["binding_status"] != allowed[action]:
            raise ValueError("绑定状态已改变或不允许此操作，请重新查看")
        target = {"approve": "APPROVED", "reject": "REJECTED", "disable": "DISABLED"}[action]
        if action == "approve":
            if not user_id or not workspace_id:
                raise ValueError("批准时必须指定经过核实的 user-id 与 workspace-id")
            cursor.execute("SELECT u.username FROM manage_user u JOIN manage_workspace w ON w.workspace_id=u.workspace_id WHERE u.user_id=%s AND u.workspace_id=%s AND u.user_type IN (1,2)", (user_id, workspace_id))
            users = cursor.fetchall()
            if len(users) != 1 or users[0]["username"] != identity["requested_username"]:
                raise ValueError("申请账号与指定用户或工作空间不一致，拒绝批准")
            cursor.execute("UPDATE miniapp_identity SET user_id=%s,workspace_id=%s WHERE identity_id=%s", (user_id, workspace_id, identity_id))
        now = int(time.time() * 1000)
        cursor.execute("UPDATE miniapp_identity SET binding_status=%s,reviewed_by=%s,review_reason=%s,update_time=%s WHERE identity_id=%s", (target, operator, reason, now, identity_id))
        cursor.execute("UPDATE miniapp_session SET revoked=TRUE WHERE identity_id=%s", (identity_id,))
        cursor.execute("DELETE FROM miniapp_login_ticket WHERE identity_id=%s", (identity_id,))
        cursor.execute("INSERT INTO miniapp_binding_audit(audit_id,identity_id,action,actor,reason,create_time) VALUES(%s,%s,%s,%s,%s,%s)", (str(uuid.uuid4()), identity_id, target, operator, reason, now))
    conn.commit()
    return {"identityId": identity_id, "bindingStatus": target}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["migrate", "list", "approve", "reject", "disable", "cleanup"])
    parser.add_argument("identity_id", nargs="?")
    for flag in ["user-id", "workspace-id", "operator", "reason"]:
        parser.add_argument("--" + flag)
    args = parser.parse_args()
    import pymysql
    required = ["MINIAPP_DB_HOST", "MINIAPP_DB_NAME", "MINIAPP_DB_USER", "MINIAPP_DB_PASSWORD"]
    if any(not os.environ.get(key) for key in required):
        raise ValueError("先在服务端配置 MINIAPP_DB_HOST/NAME/USER/PASSWORD，可选 PORT 与 SSL_CA")
    options = dict(host=os.environ[required[0]], database=os.environ[required[1]], user=os.environ[required[2]], password=os.environ[required[3]],
                   port=int(os.environ.get("MINIAPP_DB_PORT", "3306")), charset="utf8mb4", autocommit=False, connect_timeout=5,
                   cursorclass=pymysql.cursors.DictCursor)
    if os.environ.get("MINIAPP_DB_SSL_CA"):
        options["ssl"] = {"ca": os.environ["MINIAPP_DB_SSL_CA"], "check_hostname": True}
    with pymysql.connect(**options) as conn:
        if args.action == "migrate":
            # Additive, explicit migration; existing business tables are never altered.
            source = Path(__file__).resolve().parents[1] / "sql/migrations/2026-09-15-miniapp-sessions.sql"
            with conn.cursor() as cursor:
                for sql in source.read_text(encoding="utf-8").split(";"):
                    if sql.strip():
                        cursor.execute(sql)
            conn.commit()
            print("小程序身份、会话、状态和审核记录表已准备")
        elif args.action == "list":
            with conn.cursor() as cursor:
                cursor.execute("SELECT identity_id,binding_status,requested_username,display_name,organization,user_id,workspace_id,update_time FROM miniapp_identity ORDER BY update_time DESC LIMIT 100")
                print(json.dumps(cursor.fetchall(), ensure_ascii=False, indent=2))
        elif args.action == "cleanup":
            now = int(time.time() * 1000)
            with conn.cursor() as cursor:
                cursor.execute("DELETE FROM miniapp_login_ticket WHERE expires_at<%s", (now,))
                cursor.execute("DELETE FROM miniapp_session WHERE refresh_expires_at<%s", (now,))
            conn.commit()
            print("已清理过期临时票据和过期会话，保留绑定审核记录")
        else:
            if not args.identity_id:
                raise ValueError("需要指定 identity_id")
            print(json.dumps(review(conn, args.action, args.identity_id, args.user_id, args.workspace_id, args.operator, args.reason), ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except ValueError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    except Exception as error:
        # Driver exceptions may contain connection / request details.
        print("操作失败：" + type(error).__name__ + "；请核查服务端配置、表结构和数据库权限。", file=sys.stderr)
        sys.exit(1)
