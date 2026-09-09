"""Local API smoke test. Writes only clearly named QA events; never issues a flight command."""
import json, urllib.request, urllib.error, uuid, datetime
from pathlib import Path
base = 'http://127.0.0.1:6789'
auth = {}
results = []
def call(path, body=None, key=None):
    headers = {'Content-Type':'application/json'}
    if auth: headers['x-auth-token'] = auth['access_token']
    if key: headers['X-Idempotency-Key'] = key
    request = urllib.request.Request(base+path, headers=headers, data=None if body is None else json.dumps(body).encode())
    response = json.load(urllib.request.urlopen(request, timeout=20))
    assert response['code'] == 0, {'path':path, 'code':response['code'], 'message':response.get('message')}
    return response.get('data')
auth = call('/manage/api/v1/demo-login', {})
wid=auth['workspace_id']; prefix='QA-CC-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
ids=[]
for i in range(3):
    eid=prefix+'-'+str(i)
    body={'event_id':eid,'source':'COMMAND_CENTER_QA','device_sn':'QA-CC-NO-AIRCRAFT','workspace_id':wid,'confidence':0.1,'fire_level':'LOW','lat':0,'lng':0,'geo_quality':'UNLOCATED','timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'release_policy':'DRY_RUN'}
    if i != 2:
        body.update(visible_image_url='http://127.0.0.1:5188/assets/visible.jpg',thermal_image_url='http://127.0.0.1:5188/assets/thermal.jpg')
    created=call('/api/fire/events',body)
    assert created['created'] and not created['mission_created'], created
    ids.append(eid)
    assert call('/api/fire/events/'+eid)['workspace_id']==wid
results.append({'case':'create/get','passed':True,'ids':ids,'flightCommands':0})
listed=call('/api/fire/events?workspaceId='+wid+'&limit=200')
assert all(e['workspace_id']==wid for e in listed)
assert all(eid in [e['event_id'] for e in listed] for eid in ids)
assert call('/api/fire/events?workspaceId=QA-NONEXISTENT-WORKSPACE&limit=200')==[]
results.append({'case':'workspace filtering','passed':True,'count':len(listed)})
reason='本地集成验证：确认测试记录，未连接飞机，不触发飞行。'
key=str(uuid.uuid4());body={'operator_id':auth['user_id'],'reason':reason}
confirmed=call('/api/fire/events/'+ids[1]+'/confirm',body,key)
assert confirmed['fire_event']['confirmed_status']=='CONFIRMED' and not confirmed['draft_mission_created']
repeat=call('/api/fire/events/'+ids[1]+'/confirm',body,key)
assert repeat['incident']['id']==confirmed['incident']['id']
history=call('/api/fire/events/'+ids[1]+'/history')
assert sum(row['action']=='CONFIRMED' for row in history)==1
assert any(row.get('decision_reason')==reason for row in history)
results.append({'case':'confirm/idempotency/reason','passed':True,'incidentId':confirmed['incident']['id']})
reason='本地集成验证：排除测试记录，复核说明应持久化。'
call('/api/fire/events/'+ids[2]+'/reject',{'operator_id':auth['user_id'],'reason':reason},str(uuid.uuid4()))
assert call('/api/fire/events/'+ids[2])['confirmed_status']=='REJECTED'
assert any(row.get('decision_reason')==reason and row['source_event_id']==auth['user_id'] for row in call('/api/fire/events/'+ids[2]+'/history'))
results.append({'case':'reject without incident persists reviewer/reason','passed':True})
# First event deliberately stays pending for browser review. All are explicitly QA-tagged and unlocated.
out=Path(__file__).parents[1]/'evidence'/'api-smoke.json'
out.write_text(json.dumps({'time':datetime.datetime.now().isoformat(),'results':results,'browserReviewEvent':ids[0]},ensure_ascii=False,indent=2))
print(json.dumps({'passed':len(results),'browserReviewEvent':ids[0]},ensure_ascii=False))
