const won=v=>`${Math.round(Number(v||0)).toLocaleString('ko-KR')}원`;
const safe=value=>window.escapeHtml(value);
let currentUsers=[];
async function request(url,options={}){const r=await fetch(url,options);if(r.status===401){location.href='/admin-2fa';throw new Error('2FA');}if(!r.ok)throw new Error(await r.text());return r.headers.get('content-type')?.includes('json')?r.json():r.text();}
const signedWon=v=>Number(v||0)>=0?`+${won(v)}`:`-${won(Math.abs(Number(v||0)))}`;
function statusHtml(u){
 const op=u.operation;
 if(!op)return '<span class="risk-badge muted">관리자</span><small>운용 대상 아님</small>';
 const verdict=op.salesVerdict?.code==='PASS'?'판매 검토':op.salesVerdict?.code==='HOLD'?'판매 보류':'관찰';
 return `<span class="risk-badge ${safe(op.severity)}">${safe(op.label)}</span><small>${safe(op.message)}</small><small>원금 대비 ${signedWon(op.equityGapKrw)} · 7일 ${won(op.sevenDay?.netProfitKrw)}</small><small>${verdict}: ${safe(op.salesVerdict?.message||'')}</small>`;
}
async function load(){
 const [users,audits,backups,metrics]=await Promise.all([request('/api/admin/users'),request('/api/admin/audits'),request('/api/admin/backups'),request('/api/admin/api-metrics')]); currentUsers=users;
 document.querySelector('#users').innerHTML=`<table><thead><tr><th>사용자</th><th>역할/승인</th><th>상태/상품성</th><th>거래소</th><th>자동매매/미결</th><th>순손익</th><th>수익 기준</th><th>관리</th></tr></thead><tbody>${users.map(u=>`<tr><td>${safe(u.username)}<small>${safe(u.displayName)}</small></td><td>${u.role}<br>${u.approved?'승인':'승인대기'} ${u.locked?'잠김':''}</td><td class="status-cell">${statusHtml(u)}</td><td>${u.connections.map(c=>`${safe(c.exchange)}:${safe(c.status)}`).join('<br>')||'미연결'}</td><td>${u.trading.active?'ON':'OFF'} / ${u.openCycles}</td><td>${won(Number(u.realizedProfitKrw)-Number(u.externalFeeKrw))}<small>외부비용 ${won(u.externalFeeKrw)}</small></td><td>${u.risk.minProfitPercent}% / ${won(u.risk.minExpectedProfitKrw)}</td><td><div class="user-actions"><button class="admin-button admin-button-danger-soft" data-stop="${safe(u.username)}">거래 정지</button><button class="admin-button admin-button-secondary" data-settings="${safe(u.username)}">수익 설정</button>${!u.approved?`<button class="admin-button admin-button-primary" data-approve="${safe(u.username)}">사용 승인</button>`:''}${u.role!=='ADMIN'?`<button class="admin-button ${u.locked?'admin-button-primary':'admin-button-warning'}" data-lock="${safe(u.username)}" data-value="${!u.locked}">${u.locked?'잠금 해제':'계정 잠금'}</button>`:''}</div></td></tr>`).join('')}</tbody></table>`;
 document.querySelector('#audits').innerHTML=`<table><thead><tr><th>시각</th><th>관리자</th><th>대상</th><th>작업</th></tr></thead><tbody>${audits.map(a=>`<tr><td>${new Date(a.createdAt).toLocaleString('ko-KR')}</td><td>${safe(a.adminUsername)}</td><td>${safe(a.targetUsername)}</td><td>${safe(a.action)}</td></tr>`).join('')}</tbody></table>`;
 document.querySelector('#backups').textContent=backups.length?backups.join(' · '):'아직 생성된 백업이 없습니다.';
 document.querySelector('#apiMetrics').textContent=Object.keys(metrics.counts).length?Object.entries(metrics.counts).map(([k,v])=>`${k}: ${v}회`).join(' · '):'현재 프로세스에서 기록된 요청 제한·최종 실패가 없습니다.';
 document.querySelectorAll('[data-stop]').forEach(b=>b.onclick=async()=>{if(confirm(`${b.dataset.stop} 자동매매를 정지할까요?`)){await request(`/api/admin/users/${encodeURIComponent(b.dataset.stop)}/stop`,{method:'POST'});load();}});
 document.querySelectorAll('[data-approve]').forEach(b=>b.onclick=async()=>{await request(`/api/admin/users/${encodeURIComponent(b.dataset.approve)}/approve`,{method:'POST'});load();});
 document.querySelectorAll('[data-lock]').forEach(b=>b.onclick=async()=>{await request(`/api/admin/users/${encodeURIComponent(b.dataset.lock)}/lock?value=${b.dataset.value}`,{method:'POST'});load();});
 document.querySelectorAll('[data-settings]').forEach(b=>b.onclick=()=>editSettings(b.dataset.settings));
}
async function editSettings(username){const u=currentUsers.find(v=>v.username===username),s={...u.risk};const min=prompt('최소 순수익률(%)',s.minProfitPercent);if(min===null)return;const expected=prompt('최소 예상수익(원)',s.minExpectedProfitKrw);if(expected===null)return;const order=prompt('주문 기준금액(원, 최소 5000)',s.orderAmountKrw);if(order===null)return;const loss=prompt('일일 최대손실(원)',s.dailyMaxLossKrw);if(loss===null)return;Object.assign(s,{minProfitPercent:Number(min),minExpectedProfitKrw:Number(expected),orderAmountKrw:Number(order),maxOrderAmountKrw:Math.max(Number(order),Number(s.maxOrderAmountKrw)),dailyMaxLossKrw:Number(loss),profileName:'USER_1',updatedAt:null});await request(`/api/admin/users/${encodeURIComponent(username)}/settings`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(s)});load();}
document.querySelector('#refresh').onclick=load;
document.querySelector('#invite').onclick=async()=>{const v=await request('/api/admin/invites',{method:'POST'});document.querySelector('#inviteResult').textContent=`초대코드: ${v.code} (24시간 유효)`;};
document.querySelector('#stopAll').onclick=async()=>{if(confirm('모든 일반 사용자의 자동매매를 즉시 정지할까요?')){await request('/api/admin/emergency-stop',{method:'POST'});load();}};
document.querySelector('#backupNow').onclick=async()=>{await request('/api/admin/backups',{method:'POST'});load();};
document.querySelector('#changePassword').onclick=async()=>{const message=document.querySelector('#passwordMessage');const response=await fetch('/api/auth/password',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({currentPassword:document.querySelector('#currentPassword').value,newPassword:document.querySelector('#newPassword').value})});const data=await response.json();message.textContent=data.message||data.error||'변경 완료';if(response.ok)setTimeout(()=>location.href='/login',800);};
load();
