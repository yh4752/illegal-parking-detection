import {describe,it,expect,vi} from 'vitest';
import {api,request,retryDelay,sessionExpired,ApiError} from '../../src/api/client';
import {validateZoneView} from '../../src/api/generated/validators';
const response=(body:unknown,status=200,headers={})=>new Response(status===204?null:JSON.stringify(body),{status,headers});
describe('same-origin JSON boundary',()=>{
 it('fetches a new CSRF token before login and after session rotation without caching credentials',async()=>{
  const fetch=vi.fn().mockResolvedValueOnce(response({token:'before'})).mockResolvedValueOnce(response({user_id:'user-01',role:'VIEWER'})).mockResolvedValueOnce(response({token:'after'}));vi.stubGlobal('fetch',fetch);
  expect(await api.login('reader','not-a-real-password')).toEqual({user_id:'user-01',role:'VIEWER'});
  expect(fetch.mock.calls.map(x=>x[0])).toEqual(['/api/csrf','/api/auth/login','/api/csrf']);
  expect(fetch.mock.calls[1][1]).toMatchObject({credentials:'same-origin',cache:'no-store',headers:{'X-CSRF-TOKEN':'before'},body:JSON.stringify({username:'reader',password:'not-a-real-password'})});
  expect(localStorage.length).toBe(0);
 });
 it('logs out using the current CSRF token and accepts an empty 204',async()=>{const fetch=vi.fn().mockResolvedValueOnce(response({token:'current'})).mockResolvedValueOnce(response(null,204));vi.stubGlobal('fetch',fetch);await api.logout();expect(fetch.mock.calls[1][1].headers['X-CSRF-TOKEN']).toBe('current');});
 it('never renders an HTML proxy error',async()=>{vi.stubGlobal('fetch',vi.fn().mockResolvedValue(new Response('<script>bad</script>',{status:503})));await expect(api.me()).rejects.toMatchObject({status:503,code:'HTTP_ERROR'});});
 it('rejects successful responses that violate the complete wire contract',async()=>{vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({zone_id:'zone-01',view_revision:0})));await expect(api.zone('zone-01')).rejects.toMatchObject({code:'INVALID_RESPONSE'});});
 it('clears the authenticated session on protected 401 but not invalid login',async()=>{
  const expired=vi.fn();sessionExpired.addEventListener('expired',expired);
  vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response({},401)));
  await expect(api.me()).rejects.toBeInstanceOf(ApiError);expect(expired).toHaveBeenCalledOnce();
  await expect(request('/auth/login',{},undefined,false)).rejects.toBeInstanceOf(ApiError);expect(expired).toHaveBeenCalledOnce();sessionExpired.removeEventListener('expired',expired);
 });
 it('preserves caller cancellation without replacing it with a network alert',async()=>{const controller=new AbortController();controller.abort();const error=new DOMException('cancelled','AbortError');vi.stubGlobal('fetch',vi.fn().mockRejectedValue(error));await expect(api.me(controller.signal)).rejects.toBe(error);});
 it('parses bounded Retry-After values',()=>{expect(retryDelay('60')).toBe(60);expect(retryDelay('Wed, 21 Oct 2015 07:28:00 GMT',Date.parse('2015-10-21T07:27:30Z'))).toBe(30);expect(retryDelay('junk')).toBeNull();expect(retryDelay('1000000')).toBe(3600);});
 it('requires all ZoneView fields and no invented fields',()=>{const zone={zone_id:'zone-01',view_revision:0,received_at:null,last_heartbeat_at:null,connection_state:'OFFLINE',snapshot:null,commands:[],image_refs:[]};expect(validateZoneView(zone)).toBe(true);expect(validateZoneView({...zone,view_revision:-1})).toBe(false);expect(validateZoneView({...zone,legal:true})).toBe(false);});
});
