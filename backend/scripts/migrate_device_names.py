"""Reviewable, optimistic device-name migration. Credentials come only from DB_* environment variables.

Run with --backup-dir <private directory>; default is a dry run. --apply backs up
DDL and changed cells before applying updates, then independently verifies them.
Technical identifiers, object keys, raw evidence, and backup tables are excluded.
"""
import argparse, datetime, json, os, re
from pathlib import Path
import pymysql

ROOT = Path(__file__).resolve().parents[2]
CATALOG = json.loads((ROOT / 'shared/device-models.json').read_text(encoding='utf-8'))['models']
FIELDS = {
    'planned_wayline': {'name': False, 'aircraft_model_key': True, 'payload_model_key': True},
    'manage_device': {'device_name': True, 'nickname': False, 'device_desc': False},
    'manage_device_dictionary': {'device_name': True, 'device_desc': False},
    'manage_firmware_model': {'device_name': True},
    'manage_device_payload': {'payload_name': True, 'payload_desc': False},
    'wayline_file': {'name': False}, 'wayline_job': {'name': False},
}
def normalize(value): return re.sub(r'[\s_-]+', '', value.upper())
LOOKUP = {normalize(v): row['name'] for row in CATALOG for v in [row['code'], row['name'], *row['aliases']]}
ORDERED = sorted(CATALOG, key=lambda row: len(row['name']), reverse=True)
ALIASES = sorted({v for row in CATALOG for v in [row['code'], *row['aliases']] if not re.match(r'^\d+-', v) and v.upper() not in {'L1','L2','P1','DOCK'}}, key=len, reverse=True)
PATTERN = re.compile(r'(?<![A-Za-z0-9_])(' + '|'.join(re.escape(v) for v in ALIASES) + r')(?![A-Za-z0-9_])', re.I)
def full(value, exact=False):
    if not value: return value
    if exact and normalize(value) in LOOKUP: return LOOKUP[normalize(value)]
    for i,row in enumerate(ORDERED): value=re.sub(re.escape(row['name']), chr(1)+str(i)+chr(2), value, flags=re.I)
    value=PATTERN.sub(lambda match: LOOKUP[normalize(match.group())], value)
    for i,row in enumerate(ORDERED): value=value.replace(chr(1)+str(i)+chr(2),row['name'])
    return value

def migrate(connection, backup_dir, apply=False):
    changes=[]; ddl={}; expansions=[]
    with connection.cursor(pymysql.cursors.DictCursor) as cursor:
        for table, fields in FIELDS.items():
            cursor.execute('SHOW FULL COLUMNS FROM `'+table+'`'); columns={row['Field']:row for row in cursor.fetchall()}
            keys=[key for key,value in columns.items() if value['Key']=='PRI']
            if len(keys)!=1: raise RuntimeError('Expected a single primary key: '+table)
            cursor.execute('SHOW CREATE TABLE `'+table+'`'); ddl[table]=list(cursor.fetchone().values())[1]
            for field,exact in fields.items():
                if field not in columns: continue
                cursor.execute('SELECT `'+keys[0]+'` id,`'+field+'` value FROM `'+table+'` WHERE `'+field+'` IS NOT NULL')
                for row in cursor.fetchall():
                    name=full(row['value'],exact)
                    if name!=row['value']: changes.append(dict(table=table,pk=keys[0],id=row['id'],column=field,old=row['value'],new=name))
                column=columns[field]; match=re.fullmatch(r'varchar\((\d+)\)',column['Type'])
                # Full model names and future titles must not be truncated by legacy short columns.
                if match and int(match[1])<256: expansions.append(dict(table=table,column=field,previous=column))
    plan={'createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'changes':changes,'ddl':ddl,'expansions':expansions}
    backup_dir.mkdir(parents=True,exist_ok=True)
    backup=backup_dir/('device-names-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'.json')
    backup.write_text(json.dumps(plan,ensure_ascii=False,indent=2,default=str),encoding='utf-8')
    if not apply: return {'plannedCells':len(changes),'backup':str(backup),'applied':False}
    with connection.cursor() as cursor:
        for item in expansions:
            column=item['previous']; definition='VARCHAR(256)'
            collation=column.get('Collation')
            if collation and re.fullmatch(r'[a-zA-Z0-9_]+',collation): definition+=' COLLATE '+collation
            definition+=' '+('NULL' if column['Null']=='YES' else 'NOT NULL')
            default=column['Default']
            if default is not None: definition+=' DEFAULT '+connection.escape(full(default,FIELDS[item['table']][item['column']]))
            if column.get('Comment'): definition+=' COMMENT '+connection.escape(column['Comment'])
            cursor.execute('ALTER TABLE `'+item['table']+'` MODIFY COLUMN `'+item['column']+'` '+definition)
    try:
        with connection.cursor() as cursor:
            for item in changes:
                cursor.execute('UPDATE `'+item['table']+'` SET `'+item['column']+'`=%s WHERE `'+item['pk']+'`=%s AND BINARY `'+item['column']+'`=BINARY %s',(item['new'],item['id'],item['old']))
                if cursor.rowcount!=1: raise RuntimeError('Concurrent change; rolled back cell updates')
        connection.commit()
    except Exception:
        connection.rollback(); raise
    with connection.cursor() as cursor:
        for item in changes:
            cursor.execute('SELECT `'+item['column']+'` FROM `'+item['table']+'` WHERE `'+item['pk']+'`=%s',(item['id'],))
            if cursor.fetchone()[0]!=item['new']: raise RuntimeError('Migration readback failed')
    return {'updatedCells':len(changes),'verifiedCells':len(changes),'expandedColumns':len(expansions),'backup':str(backup),'applied':True}

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');parser.add_argument('--backup-dir',type=Path,required=True);args=parser.parse_args()
    connection=pymysql.connect(host=os.environ['DB_HOST'],user=os.environ['DB_USER'],password=os.environ['DB_PASSWORD'],database=os.environ['DB_NAME'],port=int(os.environ.get('DB_PORT','3306')),charset='utf8mb4',connect_timeout=10)
    try: print(json.dumps(migrate(connection,args.backup_dir,args.apply),ensure_ascii=False))
    finally: connection.close()
