function R(e){return new Worker(""+new URL("worker-Be-7_nFK.js",import.meta.url).href,{name:e?.name})}const L="vfs-image.tar.gz";function m(e){return typeof e=="object"&&e!==null&&!Array.isArray(e)?e:void 0}function $(e){const t=m(e);if(t?.version!==1||!Array.isArray(t.fixtures))throw new Error(`preview fixture manifest must use version ${String(1)}`);const r=[],o=new Set;for(const n of t.fixtures){const i=m(n),a=i?.id,d=i?.label,c=i?.description,u=i?.overlays,p=Array.isArray(u)?u.filter(l=>typeof l=="string"&&l.length>0):[];if(typeof a!="string"||!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(a)||a==="none"||a==="webfs"||typeof d!="string"||d.length===0||typeof c!="string"||c.length===0||!Array.isArray(u)||u.length===0||p.length!==u.length)throw new Error("preview fixture manifest contains an invalid fixture entry");if(o.has(a))throw new Error(`preview fixture manifest repeats id "${a}"`);o.add(a),r.push({id:a,label:d,description:c,overlays:p})}const s=t.defaultFixture;if(s!==null&&(typeof s!="string"||!o.has(s)))throw new Error("preview fixture manifest defaultFixture does not name a fixture");return{version:1,defaultFixture:s,fixtures:r}}var f=class extends Error{dshRemoteStreamFailure;constructor(e,t){super(e.message,t),this.name="TunnelLogicalStreamError",this.dshRemoteStreamFailure=e.kind==="remote"?{kind:"remote",code:e.code,details:e.details}:{kind:"carrier"}}},T=class{frames=[];wake;failed=!1;failure;push(e){this.failed||(this.frames.push(e),this.wake?.(),this.wake=void 0)}fail(e){this.failed||(this.failed=!0,this.failure=e,this.frames.length=0,this.wake?.(),this.wake=void 0)}async next(){for(;this.frames.length===0;){if(this.failed)throw this.failure;await new Promise(e=>{this.wake=e})}return this.frames.shift()}};const A=500,S=new TextEncoder,w=/\/\/# sourceMappingURL=([^\r\n]+)\s*$/,y=32*1024;function U(e){const t=S.encode(e);let r="";for(let o=0;o<t.length;o+=y)r+=String.fromCharCode(...t.subarray(o,o+y));return btoa(r)}async function _(e,t,r){const o=w.exec(e);if(o?.[1]===void 0)return e;try{const s=await r(new URL(o[1],new URL(t,globalThis.location.origin)));if(!s.ok)return e.replace(w,"");const n=`data:application/json;charset=utf-8;base64,${U(await s.text())}`;return e.replace(w,`//# sourceMappingURL=${n}`)}catch{return e.replace(w,"")}}function F(e){if(e!=null){if(typeof e=="string")return S.encode(e).buffer;if(e instanceof ArrayBuffer)return e;if(ArrayBuffer.isView(e))return e.buffer.slice(e.byteOffset,e.byteOffset+e.byteLength);throw new Error(`web-preview tunnel: unsupported request body ${Object.prototype.toString.call(e)}`)}}const O=new Set([101,204,205,304]);var M=class{worker;nextId=1;unary=new Map;bodyStreams=new Map;logicalStreams=new Map;inFlight=new Map;releases=new Map;constructor(e){this.worker=e,e.addEventListener("message",t=>{this.receive(t.data)}),e.addEventListener("error",t=>{const r=new Error(`web-preview tunnel: worker failed: ${t.message}`);for(const s of this.inFlight.keys())this.warnRefusal(s,`worker failed: ${t.message}`);this.inFlight.clear();for(const s of this.unary.values())s.reject(r);this.unary.clear();for(const s of this.bodyStreams.values())s.error(r);this.bodyStreams.clear();const o=new f({kind:"carrier",message:`web-preview tunnel: worker failed: ${t.message}`},{cause:r});for(const s of this.logicalStreams.values())s.fail(o);this.logicalStreams.clear();for(const s of this.releases.values())s();this.releases.clear()})}init(e,t=[]){this.worker.postMessage({t:"init",image:e,overlays:t})}fetch=async(e,t)=>{const r=t?.signal;if(r?.aborted===!0)throw new DOMException("The operation was aborted.","AbortError");const o=this.nextId++,s={t:"req",id:o,method:t?.method??"GET",url:new URL(e,globalThis.location.origin).toString(),headers:Object.fromEntries(new Headers(t?.headers).entries()),...t?.body===void 0||t.body===null?{}:{body:F(t.body)}},n=new Promise((a,d)=>{this.unary.set(o,{resolve:a,reject:d})});if(this.inFlight.set(o,`${s.method} ${s.url}`),this.worker.postMessage(s),r==null)return await n;const i=this.rejectOnAbort(o,r);try{const a=await Promise.race([n,i.rejected]);return this.bodyStreams.has(o)&&this.observeStreamAbort(o,r),a}finally{i.release()}};async*open(e,t,r){r.throwIfAborted();const o=this.nextId++,s=new T;let n=!1,i=!1;const a=()=>{s.fail(r.reason)};r.addEventListener("abort",a,{once:!0}),this.logicalStreams.set(o,s),this.inFlight.set(o,`STREAM ${e}`);try{const d={t:"stream-open",id:o,endpoint:e,payload:t};try{this.worker.postMessage(d),n=!0}catch(c){throw new f({kind:"carrier",message:`web-preview tunnel: failed to open Remote stream ${e}`},{cause:c})}for(;;){const c=await s.next();if(r.throwIfAborted(),c.t==="stream-item"){yield c.value;continue}if(i=!0,c.t==="stream-error")throw new f(c.failure);return}}finally{r.removeEventListener("abort",a),this.logicalStreams.delete(o),this.inFlight.delete(o),n&&!i&&this.abortWorkerOperation(o)}}async bootPayload(){const e=await this.fetch("/__boot__");if(!e.ok)throw new Error(`web-preview tunnel: boot payload failed with HTTP ${String(e.status)}: ${await e.text()}`);return await e.json()}async loadBundle(e){const t=await this.fetch(e);if(!t.ok)throw new Error(`web-preview tunnel: bundle ${e} failed with HTTP ${String(t.status)}`);const r=await _(await t.text(),e,this.fetch),o=URL.createObjectURL(new Blob([r],{type:"text/javascript"}));try{await new Promise((s,n)=>{const i=document.createElement("script");i.src=o,i.addEventListener("load",()=>{i.remove(),s()},{once:!0}),i.addEventListener("error",()=>{i.remove(),n(new Error(`web-preview tunnel: bundle ${e} failed to execute`))},{once:!0}),document.head.append(i)})}finally{URL.revokeObjectURL(o)}}rejectOnAbort(e,t){let r=()=>{};return{rejected:new Promise((o,s)=>{const n=()=>{s(this.abortRequest(e))};if(t.aborted){n();return}t.addEventListener("abort",n,{once:!0}),r=()=>{t.removeEventListener("abort",n)}}),release:r}}abortRequest(e){this.unary.delete(e);const t=this.bodyStreams.get(e);this.bodyStreams.delete(e),this.inFlight.delete(e),this.releases.delete(e),this.abortWorkerOperation(e);const r=new DOMException("The operation was aborted.","AbortError");return t?.error(r),r}observeStreamAbort(e,t){const r=()=>{this.abortRequest(e)};t.addEventListener("abort",r,{once:!0}),this.releases.set(e,()=>{t.removeEventListener("abort",r)})}releaseSignal(e){const t=this.releases.get(e);this.releases.delete(e),t?.()}cancelStream(e){this.releaseSignal(e),this.bodyStreams.delete(e),this.inFlight.delete(e),this.abortWorkerOperation(e)}abortWorkerOperation(e){const t={t:"abort",id:e};try{this.worker.postMessage(t)}catch{}}warnRefusal(e,t){console.warn(`web-preview tunnel: request ${String(e)} ${this.inFlight.get(e)??"(unknown request)"} → ${t}`)}receive(e){switch(e.t){case"res":{const t=this.unary.get(e.id);if(t===void 0)return;e.status>=A&&this.warnRefusal(e.id,`HTTP ${String(e.status)}${e.message===void 0?"":`: ${e.message}`}`),this.unary.delete(e.id),this.inFlight.delete(e.id);const r=O.has(e.status)?null:e.body??e.message??null;t.resolve(new Response(r,{status:e.status,headers:e.headers}));return}case"res-head":{const t=this.unary.get(e.id);if(t===void 0)return;this.unary.delete(e.id);const r=new ReadableStream({start:o=>{this.bodyStreams.set(e.id,o)},cancel:()=>{this.cancelStream(e.id)}});t.resolve(new Response(r,{status:e.status,headers:e.headers}));return}case"res-chunk":this.bodyStreams.get(e.id)?.enqueue(new Uint8Array(e.chunk));return;case"res-end":{const t=this.bodyStreams.get(e.id);if(t===void 0)return;this.bodyStreams.delete(e.id),this.inFlight.delete(e.id),this.releaseSignal(e.id),t.close();return}case"res-err":{const t=new Error(`web-preview tunnel: ${e.message}`);this.warnRefusal(e.id,`res-err: ${e.message}`);const r=this.unary.get(e.id);if(this.inFlight.delete(e.id),r!==void 0){this.unary.delete(e.id),r.reject(t);return}const o=this.bodyStreams.get(e.id);if(o===void 0)return;this.bodyStreams.delete(e.id),this.releaseSignal(e.id),o.error(t);return}case"stream-item":case"stream-end":case"stream-error":this.logicalStreams.get(e.id)?.push(e);return;default:throw new Error(`web-preview tunnel: unknown frame ${JSON.stringify(e)}`)}}};function j(e){throw new Error(`webworker-runtime: unknown index injection row ${JSON.stringify(e)}`)}async function P(e,t){for(const r of e)switch(r.kind){case"global":globalThis[r.name]=r.value;break;case"script":{const o=document.createElement("script");o.textContent=r.text,(r.placement==="head"?document.head:document.body).append(o);break}case"script-src":await t(r.src);break;case"script-preload":break;case"style":{const o=document.createElement("style");o.textContent=r.text,document.head.append(o);break}case"html":(r.placement==="head"?document.head:document.body).insertAdjacentHTML("beforeend",r.html);break;default:j(r)}}const b="none",I="webfs",B="preview-fixture",z=`
  [data-preview-source-chooser] {
    position: fixed;
    inset: 0;
    z-index: 1200;
    display: grid;
    place-items: center;
    overflow: auto;
    padding: 24px;
    box-sizing: border-box;
    color: #0f1115;
    background: #fff;
    font-size: 14px;
    line-height: 22px;
  }
  [data-preview-source-card] {
    width: min(600px, 100%);
    max-height: calc(100dvh - 48px);
    box-sizing: border-box;
    padding: 28px;
    overflow-y: auto;
    border: 1px solid transparent;
    border-radius: 24px;
    background: #fff;
    box-shadow: 0 0 1px rgb(0 0 0 / 20%), 0 12px 32px rgb(0 0 0 / 8%);
  }
  [data-preview-source-card] h1 {
    margin: 0;
    font-size: 20px;
    line-height: 28px;
    font-weight: 500;
  }
  [data-preview-source-card] > p {
    margin: 8px 0 0;
    color: #61666b;
  }
  [data-preview-source-card] fieldset {
    display: flex;
    flex-direction: column;
    gap: 1px;
    margin: 24px 0 0;
    padding: 0;
    border: 0;
  }
  [data-preview-source-card] legend {
    margin: 0 0 8px;
    padding: 0 4px;
    color: #61666b;
    font-size: 13px;
    line-height: 20px;
    font-weight: 500;
  }
  [data-preview-source-option] {
    position: relative;
    display: flex;
    align-items: flex-start;
    gap: 8px;
    min-height: 56px;
    padding: 8px 12px 8px 8px;
    box-sizing: border-box;
    border: 1px solid transparent;
    border-radius: 12px;
    background: transparent;
    cursor: pointer;
    transition: background-color 120ms ease, border-color 120ms ease;
  }
  [data-preview-source-option]:hover:not(:has(input:disabled)),
  [data-preview-source-option]:has(input:checked) {
    background: rgb(38 49 72 / 6%);
  }
  [data-preview-source-option]:has(input:checked) {
    border-color: rgb(0 0 0 / 10%);
  }
  [data-preview-source-option]:has(input:disabled) {
    cursor: default;
    opacity: 0.4;
  }
  [data-preview-source-option] input {
    flex: none;
    width: 16px;
    height: 16px;
    margin: 4px 0 0;
    accent-color: #0f1115;
  }
  [data-preview-source-option] > span { flex: 1; min-width: 0; }
  [data-preview-source-option] strong {
    display: block;
    font-size: 14px;
    line-height: 24px;
    font-weight: 500;
  }
  [data-preview-source-option] strong + span {
    display: block;
    color: #81858c;
    font-size: 14px;
    line-height: 24px;
  }
  [data-preview-source-submit] {
    display: block;
    min-width: 120px;
    height: 36px;
    margin: 24px 0 0 auto;
    padding: 0 14px;
    border: 0;
    border-radius: 18px;
    color: #fff;
    background: #0f1115;
    font-size: 14px;
    line-height: 22px;
    cursor: pointer;
    transition: background-color 120ms ease;
  }
  [data-preview-source-submit]:hover:not(:disabled) {
    background: #43454a;
  }
  [data-preview-source-submit]:focus-visible {
    outline: 2px solid rgb(0 0 0 / 16%);
    outline-offset: 2px;
  }
  [data-preview-source-submit]:disabled { cursor: not-allowed; opacity: 0.5; }
  @media (prefers-color-scheme: dark) {
    [data-preview-source-chooser] {
      color: #f9fafb;
      background: #151517;
    }
    [data-preview-source-card] { border-color: rgb(255 255 255 / 6%); background: #2c2c2e; }
    [data-preview-source-card] > p, [data-preview-source-card] legend { color: #cfd3d6; }
    [data-preview-source-option] strong + span { color: #adb2b8; }
    [data-preview-source-option]:hover:not(:has(input:disabled)),
    [data-preview-source-option]:has(input:checked) { background: rgb(255 255 255 / 8%); }
    [data-preview-source-option]:has(input:checked) { border-color: rgb(255 255 255 / 12%); }
    [data-preview-source-option] input { accent-color: #f9fafb; }
    [data-preview-source-submit] { color: #0f1115; background: #f9fafb; }
    [data-preview-source-submit]:hover:not(:disabled) { background: #ebeef2; }
    [data-preview-source-submit]:focus-visible { outline-color: rgb(255 255 255 / 20%); }
  }
  @media (max-width: 560px) {
    [data-preview-source-card] { padding: 24px; }
    [data-preview-source-submit] { width: 100%; }
  }
  @media (prefers-reduced-motion: reduce) {
    [data-preview-source-option], [data-preview-source-submit] { transition: none; }
  }
`,W={"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"};function x(e){return e.replace(/[&<>"']/g,t=>W[t]??t)}function C(e,t){return`<label data-preview-source-option>
    <input type="radio" name="preview-source" value="${e.id}"${e.id===t?" checked":""}${e.disabled===!0?" disabled":""}>
    <span>
      <strong>${x(e.label)}</strong>
      <span>${x(e.description)}</span>
    </span>
  </label>`}function H(e,t){return e.map(r=>({id:r.id,label:r.label,description:r.description,overlays:r.overlays.map(o=>new URL(o,t))}))}async function q(e){const t=new URL(location.href).searchParams.get(B);if(t===b)return[];const r=await fetch(e);if(!r.ok)throw new Error(`preview source chooser: fixture manifest returned ${String(r.status)}`);const o=$(await r.json()),s=[{id:b,label:"Empty environment",description:"Load only the base runtime to verify first launch and workspace creation.",overlays:[]},...H(o.fixtures,e),{id:I,label:"WebFS directory",description:"Requires directory access and will be available after the WebFS provider lands.",overlays:[],disabled:!0}];if(t!==null){const l=s.find(h=>h.id===t&&h.disabled!==!0);if(l===void 0)throw new Error(`preview source chooser: unknown or interactive source "${t}"`);return l.overlays}const n=document.getElementById("root");if(n===null)throw new Error("preview source chooser: missing #root");const i=o.defaultFixture??b,a=document.createElement("style");a.dataset.previewSourceStyle="",a.textContent=z,document.head.append(a);const d=document.createElement("main");d.dataset.previewSourceChooser="",d.innerHTML=`<form data-preview-source-card aria-labelledby="preview-source-title">
      <h1 id="preview-source-title">Choose Preview data</h1>
      <p>Data mounts before the Worker and application start. Refresh to choose again.</p>
      <fieldset>
        <legend>Filesystem source</legend>
        ${s.map(l=>C(l,i)).join("")}
      </fieldset>
      <button data-preview-source-submit type="submit">Start Preview</button>
    </form>`,n.prepend(d);const c=d.querySelector("[data-preview-source-card]");if(c===null)throw new Error("preview source chooser: form was not rendered");const u=await new Promise((l,h)=>{c.addEventListener("submit",E=>{E.preventDefault();const v=new FormData(c).get("preview-source");typeof v=="string"?l(v):h(new Error("preview source chooser: no source selected"))},{once:!0})}),p=s.find(l=>l.id===u&&l.disabled!==!0);if(p===void 0)throw new Error(`preview source chooser: unavailable source "${u}"`);return d.remove(),a.remove(),p.overlays}function g(){return globalThis.__DSH_BOOT_READY__??=Promise.withResolvers()}function D(){g().promise.catch(()=>{})}async function N(e={}){D();const t=new URL(e.image??"vfs-image.tar.gz",document.baseURI),r=new URL(e.fixtureManifest??"fixtures.json",t);try{return{overlays:await q(r)}}catch(o){throw g().reject(o),o}}async function Y(e,t){const r=g();r.promise.catch(()=>{});try{const o=new M(e);o.init(new URL(t?.image??"vfs-image.tar.gz",document.baseURI).href,(t?.overlays??[]).map(n=>new URL(n,document.baseURI).href));const s=await o.bootPayload();return globalThis.__DSH_TRANSPORT__={fetch:(n,i)=>o.fetch(n,i),openStream:(n,i,a)=>o.open(n,i,a),loadBundle:n=>o.loadBundle(n),ownsHost:!0},await P(s.injections,n=>o.loadBundle(n)),r.resolve(),{worker:e,tunnel:o,loadBundle:n=>o.loadBundle(n)}}catch(o){throw r.reject(o),o}}const k=`preview/${L}`,G=await N({image:k});await Y(new R({name:"dsh-host"}),{image:k,overlays:G.overlays});
//# sourceMappingURL=bootstrap-53KaPJhF.js.map
