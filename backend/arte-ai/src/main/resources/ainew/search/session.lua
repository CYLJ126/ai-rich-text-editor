local op=ARGV[1]
local p=cjson.decode(ARGV[2])
local clock=redis.call('TIME')
local now=tonumber(clock[1])*1000+math.floor(tonumber(clock[2])/1000)
local lease=tonumber(ARGV[3])
local idle=tonumber(ARGV[4])
local lifetime=tonumber(ARGV[5])
local cap=tonumber(ARGV[6])
redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
local function activeTtl()
  local last=redis.call('ZREVRANGE',KEYS[1],0,0,'WITHSCORES')
  if #last==0 then redis.call('DEL',KEYS[1]) else redis.call('PEXPIREAT',KEYS[1],math.floor(tonumber(last[2]))) end
end
local function save(s,expires)
  s.serverNow=now
  redis.call('SET',KEYS[2],cjson.encode(s),'PXAT',math.floor(expires))
  redis.call('ZADD',KEYS[1],expires,s.id)
  activeTtl()
  return cjson.encode(s)
end
local raw=redis.call('GET',KEYS[2])
local s=raw and cjson.decode(raw) or nil
if op=='RESERVE' then
  if s then return '!EXPIRED' end
  if redis.call('ZCARD',KEYS[1])>=cap then return '!LIMIT' end
  p.createdAt=now;p.lastSuccessAt=0;p.hardExpiresAt=now+lifetime
  p.idleExpiresAt=math.min(now+idle,now+lease,p.hardExpiresAt)
  p.pitExpiresAt=0;p.operationUntil=p.idleExpiresAt
  return save(p,p.idleExpiresAt)
end
if op=='CLOSE' or op=='CANCEL' then
  if s and s.ownerKey~=p.ownerKey then return '' end
  if s and s.closed then return '' end
  if not s and op=='CLOSE' then return '' end
  if s then redis.call('ZREM',KEYS[1],s.id) end
  activeTtl()
  redis.call('SET',KEYS[2],cjson.encode({closed=true,ownerKey=p.ownerKey,id=p.id}),'PX',math.max(30000,lease))
  if s then s.serverNow=now;return cjson.encode(s) else return '' end
end
if not s or s.closed or s.ownerKey~=p.ownerKey or s.idleExpiresAt<=now or s.hardExpiresAt<=now then return '!EXPIRED' end
if op=='ACQUIRE' then
  if s.queryHash~=p.queryHash or s.size~=p.size then return '!BOUND' end
  if s.operationUntil>now then return '!BUSY' end
  if s.pitExpiresAt<=now then return '!EXPIRED' end
  s.operationToken=p.operationToken;s.operationUntil=math.min(now+lease,s.idleExpiresAt,s.hardExpiresAt)
  return save(s,s.idleExpiresAt)
end
if s.operationToken~=p.operationToken or s.operationUntil<=now then return '!EXPIRED' end
if op=='PIT' then
  s.pitId=p.pitId;s.generation=p.generation;s.pitExpiresAt=math.min(p.pitExpiresAt,s.hardExpiresAt)
  return save(s,s.idleExpiresAt)
end
if op=='COMPLETE' then
  if s.pitId=='' or s.pitExpiresAt<=now then return '!EXPIRED' end
  s.lastSuccessAt=now;s.idleExpiresAt=math.min(now+idle,s.hardExpiresAt,s.pitExpiresAt)
  s.operationToken='';s.operationUntil=0
  return save(s,s.idleExpiresAt)
end
if op=='RELEASE' then
  s.operationToken='';s.operationUntil=0
  return save(s,s.idleExpiresAt)
end
return '!BOUND'
