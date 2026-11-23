import React, { useEffect, useRef, useState } from 'react';
import {
  Activity, AlertTriangle, CalendarClock, Camera, CheckCircle2, ChevronRight,
  ClipboardCheck, Clock3, Download, Eye, LayoutDashboard, LockKeyhole, LogOut,
  Play, Plus, RefreshCw, ScanLine, ScrollText, ShieldCheck, Square, UsersRound,
  Video, Wifi, XCircle
} from 'lucide-react';

const API = (import.meta.env.VITE_API_URL || 'http://localhost:8080').replace(/\/$/, '');
const NAV = [
  ['overview', LayoutDashboard, 'Overview'],
  ['sessions', CalendarClock, 'Sessions'],
  ['reviews', ClipboardCheck, 'Review queue'],
  ['identities', UsersRound, 'Identity registry'],
  ['cameras', Camera, 'Cameras'],
  ['audit', ScrollText, 'Audit history']
];
const fmt = (value) => value ? new Date(value).toLocaleString() : '—';
const errText = (e) => e instanceof Error ? e.message : String(e);
function Chip({ children, tone = 'neutral' }) {
  return <span className={`chip chip-${tone}`}>{children}</span>;
}
function Empty({ title, text }) {
  return <div className="empty"><ScanLine size={31} strokeWidth={1.3} /><strong>{title}</strong><p>{text}</p></div>;
}
function SectionHeader({ title, sub, action }) {
  return <div className="section-header"><div><h2>{title}</h2><p>{sub}</p></div>{action}</div>;
}
function Stat({ value, label, icon: Icon, note }) {
  return <article className="stat"><span className="stat-icon"><Icon size={19}/></span><div className="stat-value">{value ?? '—'}</div><div className="stat-label">{label}</div>{note && <small>{note}</small>}</article>;
}
function Button({ children, kind = 'primary', icon: Icon, ...props }) {
  return <button className={`btn btn-${kind}`} {...props}>{Icon && <Icon size={15}/>} {children}</button>;
}

export default function App() {
  const [credentials, setCredentials] = useState(null);
  const [login, setLogin] = useState({username:'', password:''});
  const [loginBusy, setLoginBusy] = useState(false);
  const [tab, setTab] = useState('overview');
  const [alert, setAlert] = useState('');
  const [loading, setLoading] = useState(false);
  const [dashboard, setDashboard] = useState({});
  const [sessions, setSessions] = useState([]);
  const [reviews, setReviews] = useState([]);
  const [identities, setIdentities] = useState([]);
  const [cameras, setCameras] = useState([]);
  const [audit, setAudit] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [windows, setWindows] = useState([]);
  const [checkins, setCheckins] = useState([]);
  const [newSession, setNewSession] = useState({title:'',environment:'CLASSROOM',plannedMinutes:30,cameraAlias:''});
  const [newIdentity, setNewIdentity] = useState({externalCode:'',displayName:'',memberType:'STUDENT'});
  const [newCamera, setNewCamera] = useState({alias:'',label:'',sourceType:'BROWSER'});
  const [newCheckin, setNewCheckin] = useState({identityId:'',method:'MANUAL',evidenceRef:''});
  const [manualCount, setManualCount] = useState(20);
  const [reviewNotes, setReviewNotes] = useState({});
  const [liveState, setLiveState] = useState('idle');
  const [liveResult, setLiveResult] = useState(null);
  const videoEl = useRef(null);
  const cameraStream = useRef(null);
  const cameraTimer = useRef(null);
  const captureBusy = useRef(false);

  const isAdmin = credentials?.roles?.includes('ROLE_ADMIN');
  const selected = sessions.find(s => s._id === selectedId);

  async function http(path, init = {}, authOverride = credentials) {
    if (!authOverride) throw new Error('Please sign in.');
    const headers = { Authorization: authOverride.authorization, ...(init.headers || {}) };
    if (init.body && !(init.body instanceof FormData)) headers['Content-Type'] = 'application/json';
    let response;
    try { response = await fetch(API + path, {...init, headers}); }
    catch { throw new Error('API unreachable. Check Spring Boot at ' + API); }
    if (!response.ok) {
      const responseBody = await response.text();
      let detail = '';
      try { const json = JSON.parse(responseBody); detail = json.detail || json.message || json.error || ''; }
      catch { detail = responseBody.slice(0, 120); }
      throw new Error(`HTTP ${response.status}: ${detail || 'Request failed'}`);
    }
    if (response.status === 204) return null;
    if (response.headers.get('content-type')?.includes('text/csv')) return response.blob();
    return response.json();
  }

  async function signIn(event) {
    event.preventDefault();
    setLoginBusy(true);
    setAlert('');
    try {
      // Credentials live only in React memory; refreshing the page signs out.
      const auth = {authorization:'Basic ' + btoa(login.username + ':' + login.password)};
      const result = await http('/api/me', {}, auth);
      setCredentials({...auth, username:result.username, roles:result.roles});
      setLogin({username:'',password:''});
    } catch (e) { setAlert(errText(e)); }
    finally { setLoginBusy(false); }
  }

  async function refresh() {
    if (!credentials) return;
    setLoading(true);
    try {
      const [d,s,r,c] = await Promise.all([
        http('/api/dashboard'), http('/api/sessions'),
        http('/api/reviews?status=ALL'), http('/api/cameras')
      ]);
      setDashboard(d); setSessions(s); setReviews(r); setCameras(c);
      if (!selectedId && s.length) setSelectedId(s[0]._id);
      const people = await http('/api/identities').catch(() => []);
      setIdentities(people);
      if (isAdmin) setAudit(await http('/api/audit'));
    } catch (e) { setAlert(errText(e)); }
    finally { setLoading(false); }
  }
  useEffect(() => { if (credentials) refresh(); }, [credentials]);
  useEffect(() => {
    if (!credentials || !selectedId) { setWindows([]); setCheckins([]); return; }
    Promise.all([http(`/api/sessions/${selectedId}/windows`),
                 http(`/api/sessions/${selectedId}/checkins`)])
      .then(([w,c]) => { setWindows(w); setCheckins(c); })
      .catch(e => setAlert(errText(e)));
  }, [credentials, selectedId, sessions]);

  async function act(fn, notice = 'Saved.') {
    setAlert('');
    try {
      await fn();
      setAlert(notice);
      await refresh();
    } catch (e) { setAlert(errText(e)); }
  }
  async function createSession(e) {
    e.preventDefault();
    await act(async () => {
      const s=await http('/api/sessions',{method:'POST',body:JSON.stringify(newSession)});
      setSelectedId(s._id); setNewSession({title:'',environment:'CLASSROOM',plannedMinutes:30,cameraAlias:''});
      setTab('sessions');
    }, 'Session created. Start it to collect observation evidence.');
  }
  async function downloadReport() {
    try {
      if (!selectedId) return;
      const blob = await http(`/api/sessions/${selectedId}/report.csv`);
      const link=document.createElement('a'), url=URL.createObjectURL(blob);
      link.href=url; link.download=`presenceiq_${selectedId}.csv`; link.click();
      URL.revokeObjectURL(url);
    } catch (e) { setAlert(errText(e)); }
  }
  function stopBrowser() {
    if (cameraTimer.current) clearInterval(cameraTimer.current);
    cameraTimer.current = null;
    if (cameraStream.current) cameraStream.current.getTracks().forEach(t => t.stop());
    cameraStream.current = null;
    if (videoEl.current) videoEl.current.srcObject=null;
    setLiveState('idle');
  }
  useEffect(() => () => {
    if (cameraTimer.current) clearInterval(cameraTimer.current);
    if (cameraStream.current) cameraStream.current.getTracks().forEach(t => t.stop());
  }, []);
  useEffect(() => { stopBrowser(); setLiveResult(null); }, [selectedId]);

  async function capture() {
    if (captureBusy.current || !videoEl.current || !cameraStream.current) return;
    captureBusy.current=true;
    try {
      const video=videoEl.current;
      if (!video.videoWidth || !video.videoHeight) return;
      const canvas=document.createElement('canvas');
      canvas.width=640; canvas.height=Math.round(640*video.videoHeight/video.videoWidth);
      canvas.getContext('2d').drawImage(video,0,0,canvas.width,canvas.height);
      const blob=await new Promise(resolve => canvas.toBlob(resolve,'image/jpeg',0.82));
      if(!blob) return;
      const body=new FormData(); body.append('file',blob,'webcam.jpg');
      const result=await http(`/api/sessions/${selectedId}/frame`,{method:'POST',body});
      setLiveResult(result);
      setLiveState('streaming');
      // Do not store image pixels; only observation metadata reaches the backend.
    } catch (e) {
      setLiveState('error'); setAlert(errText(e)); stopBrowser();
    } finally { captureBusy.current=false; }
  }
  async function startBrowser() {
    if (!selected || selected.status!=='ACTIVE') { setAlert('Start an active session first.'); return; }
    if (!navigator.mediaDevices?.getUserMedia) { setAlert('Browser camera requires localhost or HTTPS.'); return; }
    try {
      const stream=await navigator.mediaDevices.getUserMedia({video:{width:1280,height:720},audio:false});
      cameraStream.current=stream;
      if (videoEl.current) {
        videoEl.current.srcObject=stream;
        await videoEl.current.play();
      }
      setLiveState('starting');
      await capture();
      cameraTimer.current=setInterval(capture,4000);
    } catch (e) { setAlert(errText(e)); stopBrowser(); }
  }
  function logout() { stopBrowser(); setCredentials(null); setTab('overview'); setSelectedId(''); setAlert(''); }

  if (!credentials) return (
    <main className="login-shell">
      <section className="login-intro">
        <div className="mark large"><ScanLine size={25}/></div>
        <p className="eyebrow">INTELLIGENT OBSERVATION PLATFORM</p>
        <h1>Presence, with<br/><em>more certainty.</em></h1>
        <p>Monitor occupancy, understand temporal gaps, and make accountable decisions with a human in control.</p>
        <div className="login-metrics"><span>05<small>TIME WINDOWS</small></span><span>24/7<small>EVENT AUDIT</small></span><span>01<small>REVIEW WORKBENCH</small></span></div>
      </section>
      <form className="login-form panel" onSubmit={signIn}>
        <div className="login-lock"><LockKeyhole size={22}/></div>
        <h2>Sign in to your workspace</h2>
        <p>Authorized administrators and reviewers only. Development environment: use your configured account.</p>
        <label>Username<input required autoComplete="username" value={login.username} onChange={e=>setLogin({...login,username:e.target.value})} placeholder="admin"/></label>
        <label>Password<input required type="password" autoComplete="current-password" value={login.password} onChange={e=>setLogin({...login,password:e.target.value})} placeholder="Enter your password"/></label>
        <Button type="submit" disabled={loginBusy} icon={ShieldCheck}>{loginBusy?'Checking credentials…':'Access console'}</Button>
        {alert && <div className="notice error">{alert}</div>}
        <small className="security-note">HTTP Basic is for local development. Do not expose this build publicly without TLS and production authentication.</small>
      </form>
    </main>
  );

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand"><div className="mark"><ScanLine size={22}/></div><div><strong>PresenceIQ</strong><small>OBSERVATION CONSOLE</small></div></div>
        <p className="nav-eyebrow">WORKSPACE</p>
        <nav aria-label="Main navigation">{NAV.filter(([id])=>id!=='audit'||isAdmin).map(([id,Icon,title])=>
          <button key={id} className={tab===id?'nav-item active':'nav-item'} onClick={()=>setTab(id)}><Icon size={18}/>{title}{id==='reviews' && <span className="nav-count">{reviews.filter(r=>r.status==='PENDING').length}</span>}</button>
        )}</nav>
        <div className="sidebar-bottom"><div className="safe-note"><ShieldCheck size={19}/><div><strong>Human-led decisions</strong><small>Unverified tracks never become named attendance.</small></div></div>
          <button className="nav-item" onClick={logout}><LogOut size={18}/>Sign out</button>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumbs"><span>Workspace</span><ChevronRight size={15}/><strong>{NAV.find(([id])=>id===tab)?.[2]}</strong></div>
          <div className="top-actions"><span className="online"><i/> Development environment</span><button className="icon-button" aria-label="Refresh data" onClick={refresh} disabled={loading}><RefreshCw size={18}/></button><span className="user-avatar">{credentials.username.slice(0,2).toUpperCase()}</span></div>
        </header>

        <div className="page-content">
          <div className="page-title"><div><p className="eyebrow">PRESENCE INTELLIGENCE / V12.1</p><h1>{NAV.find(([id])=>id===tab)?.[2]}</h1></div><span className="top-pill"><Wifi size={15}/>API connected</span></div>
          {alert && <div role="status" className={alert.includes('HTTP')||alert.includes('unavailable')?'notice error':'notice'}><span>{alert}</span><button aria-label="Dismiss" onClick={()=>setAlert('')}><XCircle size={17}/></button></div>}

          {tab==='overview' && <>
            <div className="stats-grid">
              <Stat value={dashboard.sessions ?? 0} label="Monitoring sessions" icon={CalendarClock}/>
              <Stat value={dashboard.activeSessions ?? 0} label="Active now" icon={Activity} note="Sessions are controlled manually"/>
              <Stat value={dashboard.pendingReviews ?? 0} label="Pending reviews" icon={AlertTriangle}/>
              <Stat value={dashboard.cameras ?? 0} label="Registered sources" icon={Camera}/>
            </div>
            <div className="layout-two">
              <section className="panel padded"><SectionHeader title="Recent sessions" sub="Monitor progress and inspect five-window evidence" action={<Button kind="subtle" onClick={()=>setTab('sessions')}>View all</Button>}/>
                {sessions.length ? sessions.slice(0,6).map(s=><button key={s._id} className="session-line" onClick={()=>{setSelectedId(s._id);setTab('sessions');}}><span className="session-bullet"><Video size={17}/></span><span className="session-line-main"><strong>{s.title}</strong><small>{s.environment} · {s.plannedMinutes} min · {fmt(s.createdAt)}</small></span><Chip tone={s.status==='ACTIVE'?'green':'neutral'}>{s.status}</Chip><ChevronRight size={16}/></button>)
                  : <Empty title="No monitoring sessions" text="Create a session to begin collecting presence evidence."/>}
              </section>
              <section className="panel padded"><SectionHeader title="Evidence queue" sub="Situations needing a human decision" action={<Button kind="subtle" onClick={()=>setTab('reviews')}>Review all</Button>}/>
                {reviews.filter(r=>r.status==='PENDING').slice(0,5).map(r=><div className="queue-item" key={r._id}><span className="queue-icon"><AlertTriangle size={17}/></span><div><strong>{r.reason?.replaceAll('_',' ')}</strong><small>Window {Number(r.windowIndex)+1} · {r.sessionId?.slice(0,8)}</small></div><Chip tone="amber">Pending</Chip></div>)}
                {!reviews.some(r=>r.status==='PENDING') && <Empty title="Queue clear" text="New uncertainty events will appear here automatically."/>}
              </section>
            </div>
            <div className="disclaimer"><Eye size={18}/><div><strong>What this dashboard measures</strong><p>Observed people, confidence and temporary tracking evidence. It does not automatically determine whether any named student or employee attended.</p></div></div>
          </>}

          {tab==='sessions' && <>
            <div className="layout-two sessions-layout">
              <section className="panel padded"><SectionHeader title="Session manager" sub="Create, start, close and examine observation sessions"/>
                {isAdmin && <form className="form-stack" onSubmit={createSession}>
                  <label>Session name<input value={newSession.title} required maxLength={120} placeholder="e.g. CSE Lab — Monday morning" onChange={e=>setNewSession({...newSession,title:e.target.value})}/></label>
                  <div className="form-grid"><label>Environment<select value={newSession.environment} onChange={e=>setNewSession({...newSession,environment:e.target.value})}><option>CLASSROOM</option><option>OFFICE</option><option>INDUSTRIAL</option></select></label>
                    <label>Planned minutes<input type="number" min="1" max="1440" value={newSession.plannedMinutes} onChange={e=>setNewSession({...newSession,plannedMinutes:Number(e.target.value)})}/></label></div>
                  <label>Camera source (optional)<select value={newSession.cameraAlias} onChange={e=>setNewSession({...newSession,cameraAlias:e.target.value})}><option value="">No assigned source</option>{cameras.map(c=><option key={c._id} value={c._id}>{c.label} · {c.sourceType}</option>)}</select></label>
                  <Button type="submit" icon={Plus}>Create session</Button>
                </form>}
                <div className="list-stack">{sessions.map(s=><button key={s._id} onClick={()=>setSelectedId(s._id)} className={selectedId===s._id?'list-select selected':'list-select'}><div><strong>{s.title}</strong><small>{s.environment} · {s.plannedMinutes} min</small></div><Chip tone={s.status==='ACTIVE'?'green':'neutral'}>{s.status}</Chip></button>)}</div>
                {!sessions.length && <Empty title="No sessions" text="Create the first monitoring session above."/>}
              </section>
              <section className="panel padded"><SectionHeader title="Session evidence" sub={selected?selected.title:'Select a session'} action={selected&&<Button icon={Download} kind="subtle" onClick={downloadReport}>Export CSV</Button>}/>
                {!selected?<Empty title="Select a session" text="Its five temporal observation windows will appear here."/>:<>
                  <div className="session-banner"><div><small>SESSION STATUS</small><h3>{selected.status}</h3><span>Created {fmt(selected.createdAt)}</span></div><div className="session-buttons">
                    {isAdmin&&selected.status==='DRAFT'&&<Button icon={Play} onClick={()=>act(()=>http(`/api/sessions/${selected._id}/start`,{method:'POST'}),'Session started')}>Start</Button>}
                    {isAdmin&&selected.status==='ACTIVE'&&<Button icon={Square} kind="outline" onClick={()=>{stopBrowser();act(()=>http(`/api/sessions/${selected._id}/close`,{method:'POST'}),'Session closed');}}>Close</Button>}
                  </div></div>
                  <div className="window-list"><h3>Five temporal windows</h3>{windows.map(w=><div className="window-line" key={w.window}><span className="window-label">W{w.window}</span><div className="window-body"><div className="window-meta"><strong>{w.samples} samples</strong><span>Average {w.meanObservedPeople} observed</span></div><div className="bar-track"><div className="bar-value" style={{width:`${Math.min(100,w.samples*6)}%`}}/></div></div><Chip tone={w.evidenceStatus==='OBSERVED'?'green':w.evidenceStatus==='NO_DATA'?'neutral':'amber'}>{w.evidenceStatus.replaceAll('_',' ')}</Chip></div>)}</div>
                  {isAdmin&&selected.status==='ACTIVE'&&<div className="dev-test"><strong>Manual test observation</strong><p>Add a clearly labeled demo count without assigning identities.</p><div className="inline-form"><input type="number" min="0" max="1000" value={manualCount} onChange={e=>setManualCount(Number(e.target.value))}/><Button kind="outline" onClick={()=>act(()=>http(`/api/sessions/${selectedId}/observations`,{method:'POST',body:JSON.stringify({count:manualCount,trackedCount:0,meanConfidence:manualCount?0.8:0})}),'Demo observation added')}>Record demo</Button></div></div>}
                  <div className="fine-print">No-data or zero detected people must not be interpreted as verified absence.</div>
                </>}
              </section>
            </div>
          </>}

          {tab==='reviews' && <section className="panel padded"><SectionHeader title="Human review workbench" sub="Evaluate evidence; these actions do not approve named attendance"/>
            <div className="review-list">{reviews.length?reviews.map(r=><article className="review-card" key={r._id}><div className="review-main"><span className="queue-icon"><AlertTriangle size={20}/></span><div><strong>{r.reason?.replaceAll('_',' ')}</strong><p>Session {r.sessionId?.slice(0,13)} · Window {Number(r.windowIndex)+1} · {fmt(r.createdAt)}</p></div><Chip tone={r.status==='PENDING'?'amber':'green'}>{r.status.replaceAll('_',' ')}</Chip></div>
            {r.status==='PENDING'&&<div className="review-actions"><input placeholder="Reviewer note (optional)" maxLength={1500} value={reviewNotes[r._id]||''} onChange={e=>setReviewNotes({...reviewNotes,[r._id]:e.target.value})}/>{[['ACCEPT_EVIDENCE',CheckCircle2,'Accept evidence'],['REJECT_EVIDENCE',XCircle,'Reject evidence'],['NEEDS_MORE_EVIDENCE',Clock3,'Needs more']].map(([decision,Icon,label])=><Button key={decision} icon={Icon} kind={decision==='ACCEPT_EVIDENCE'?'primary':'outline'} onClick={()=>act(()=>http(`/api/reviews/${r._id}/decision`,{method:'POST',body:JSON.stringify({decision,note:reviewNotes[r._id]||''})}),'Evidence review saved')}>{label}</Button>)}</div>}
            {r.status!=='PENDING'&&<p className="review-foot">Reviewed by {r.reviewer||'—'} · {r.decisionNote||'No note'}</p>}
            </article>):<Empty title="No review events" text="Low-confidence or missing observations will create review items."/>}</div>
          </section>}

          {tab==='identities' && <div className="layout-two">
            <section className="panel padded"><SectionHeader title="Authorized identity registry" sub="Managed records only; no face recognition or automatic identity linkage"/>
              {isAdmin&&<form className="form-stack" onSubmit={e=>{e.preventDefault();act(()=>http('/api/identities',{method:'POST',body:JSON.stringify(newIdentity)}),'Identity registered');}}>
                <label>External institution ID<input required value={newIdentity.externalCode} onChange={e=>setNewIdentity({...newIdentity,externalCode:e.target.value})}/></label>
                <label>Display name<input required value={newIdentity.displayName} onChange={e=>setNewIdentity({...newIdentity,displayName:e.target.value})}/></label>
                <label>Member type<select value={newIdentity.memberType} onChange={e=>setNewIdentity({...newIdentity,memberType:e.target.value})}><option>STUDENT</option><option>EMPLOYEE</option></select></label>
                <Button type="submit" icon={Plus}>Register identity</Button></form>}
              <div className="list-stack">{identities.map(p=><div className="registry-line" key={p._id}><span className="person-avatar">{p.displayName?.slice(0,1).toUpperCase()}</span><div><strong>{p.displayName}</strong><small>{p.externalCode} · {p.memberType}</small></div></div>)}</div>
              {!identities.length&&<Empty title="Registry empty" text="Register authorized people before creating identity check-in claims."/>}
            </section>
            <section className="panel padded"><SectionHeader title="Credential evidence" sub={selected?'Session: '+selected.title:'Select a session under Sessions'}/>
              <p className="fine-print">QR/NFC/manual entries are claims until a reviewer verifies the credential evidence. They are never automatically linked to camera tracker IDs.</p>
              {isAdmin&&selected?.status==='ACTIVE'&&<form className="form-stack" onSubmit={e=>{e.preventDefault();act(()=>http('/api/checkins',{method:'POST',body:JSON.stringify({...newCheckin,sessionId:selectedId})}),'Check-in claim created');}}>
                <label>Identity<select required value={newCheckin.identityId} onChange={e=>setNewCheckin({...newCheckin,identityId:e.target.value})}><option value="">Select registered person</option>{identities.map(p=><option key={p._id} value={p._id}>{p.displayName}</option>)}</select></label>
                <label>Method<select value={newCheckin.method} onChange={e=>setNewCheckin({...newCheckin,method:e.target.value})}><option>MANUAL</option><option>QR</option><option>NFC</option></select></label>
                <label>Evidence reference<input required maxLength={120} placeholder="Authorized check-in record / reader event ID" value={newCheckin.evidenceRef} onChange={e=>setNewCheckin({...newCheckin,evidenceRef:e.target.value})}/></label>
                <Button type="submit">Create claim</Button>
              </form>}
              <div className="list-stack">{checkins.map(c=><div className="checkin" key={c._id}><strong>{identities.find(p=>p._id===c.identityId)?.displayName||c.identityId?.slice(0,8)}</strong><small>{c.method} · {c.status} · {fmt(c.createdAt)}</small>
                {c.status==='CLAIMED'&&<div className="inline-form"><Button kind="outline" onClick={()=>act(()=>http(`/api/checkins/${c._id}/verify`,{method:'POST',body:JSON.stringify({decision:'VERIFIED',note:'Credential evidence checked by reviewer.'})}),'Credential claim verified')}>Verify</Button><Button kind="outline" onClick={()=>act(()=>http(`/api/checkins/${c._id}/verify`,{method:'POST',body:JSON.stringify({decision:'REJECTED',note:'Claim could not be verified.'})}),'Claim rejected')}>Reject</Button></div>}</div>)}</div>
            </section>
          </div>}

          {tab==='cameras' && <div className="layout-two">
            <section className="panel padded"><SectionHeader title="Camera sources" sub="The registry stores aliases, never RTSP passwords"/>
              {isAdmin&&<form className="form-stack" onSubmit={e=>{e.preventDefault();act(()=>http('/api/cameras',{method:'POST',body:JSON.stringify(newCamera)}),'Camera alias registered');}}>
                <label>Camera alias<input required pattern="[a-zA-Z0-9_-]+" placeholder="lab-camera-1" value={newCamera.alias} onChange={e=>setNewCamera({...newCamera,alias:e.target.value})}/></label>
                <label>Friendly label<input required placeholder="Computer lab entrance" value={newCamera.label} onChange={e=>setNewCamera({...newCamera,label:e.target.value})}/></label>
                <label>Source type<select value={newCamera.sourceType} onChange={e=>setNewCamera({...newCamera,sourceType:e.target.value})}><option>BROWSER</option><option>RTSP</option></select></label>
                <Button icon={Plus} type="submit">Register source</Button>
              </form>}
              <div className="list-stack">{cameras.map(c=><div key={c._id} className="registry-line"><span className="session-bullet"><Camera size={19}/></span><div><strong>{c.label}</strong><small>{c._id}</small></div><Chip>{c.sourceType}</Chip></div>)}</div>
            </section>
            <section className="panel padded"><SectionHeader title="Live observation" sub="Browser webcam or trusted edge RTSP source"/>
              <div className="live-preview"><video ref={videoEl} autoPlay muted playsInline/><div className="video-placeholder">{liveState==='idle'&&<><Video size={32}/><strong>Camera is not active</strong><span>Explicit permission is required before any capture.</span></>}</div></div>
              <p className="fine-print">Select an ACTIVE session in Sessions. Browser captures are transmitted to your local vision service, without saving raw images.</p>
              <div className="inline-form">
                {isAdmin&&<Button icon={Play} disabled={liveState!=='idle'||selected?.status!=='ACTIVE'} onClick={startBrowser}>Start browser camera</Button>}
                {isAdmin&&<Button icon={Square} kind="outline" onClick={stopBrowser}>Stop</Button>}
              </div>
              {liveResult&&<div className="live-results"><strong>Last camera observation</strong><span>{liveResult.observation?.count} observed people · {liveResult.observation?.trackedCount} temporary tracks</span><small>No named attendance inferred.</small></div>}
              {isAdmin&&selected?.status==='ACTIVE'&&cameras.filter(c=>c.sourceType==='RTSP').map(c=><div className="rtsp-row" key={c._id}><div><strong>{c.label}</strong><small>Edge source · {c._id}</small></div><Button kind="outline" onClick={()=>act(()=>http(`/api/sessions/${selectedId}/streams/${c._id}/start`,{method:'POST'}),'RTSP start requested')}>Start</Button><Button kind="outline" onClick={()=>act(()=>http(`/api/sessions/${selectedId}/streams/${c._id}/stop`,{method:'POST'}),'RTSP stop requested')}>Stop</Button></div>)}
            </section>
          </div>}

          {tab==='audit'&&isAdmin&&<section className="panel padded"><SectionHeader title="Audit history" sub="Immutable evidence, review and session action records"/>
            {audit.length?<div className="audit-table"><div className="table-head"><span>Event</span><span>Entity</span><span>Actor</span><span>Timestamp</span></div>{audit.map(a=><div className="table-row" key={a._id}><strong>{a.verb}</strong><span>{a.entity} / {a.entityId?.slice(0,8)}</span><span>{a.actor}</span><span>{fmt(a.occurredAt)}</span></div>)}</div>
            :<Empty title="No events yet" text="Actions will be recorded here once sessions are created."/>}
          </section>}
          <footer>PresenceIQ <span>·</span> Observation intelligence with a human in control <span>·</span> Development build</footer>
        </div>
      </div>
    </div>
  );
}
