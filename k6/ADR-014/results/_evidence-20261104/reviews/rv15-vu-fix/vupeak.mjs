function fnv1a(str){let h=0x811c9dc5>>>0;for(let i=0;i<str.length;i++){h^=str.charCodeAt(i);h=Math.imul(h,0x01000193)>>>0;}return h>>>0;}
function mulberry32(seed){let a=seed>>>0;return function(){a=(a+0x6d2b79f5)>>>0;let t=a;t=Math.imul(t^(t>>>15),t|1);t^=t+Math.imul(t^(t>>>7),t|61);return((t^(t>>>14))>>>0)/4294967296;};}
const rate=20,N=600*rate;
for (const fExtB of [0,0.5]) for (let rep=1;rep<=3;rep++){
  const ev={main:[],rehold:[]};
  for(let sid=0;sid<N;sid++){const r=mulberry32(fnv1a(`adr015|${rep}|${sid}`));r();r();const rh=r()<0.5;const xb=r()<fExtB;
    const t=sid*1000/rate; ev.main.push([t,1],[t+(31+(xb?110:0))*1000,-1]);
    if(rh) ev.rehold.push([t,1],[t+(43+(xb?110:0))*1000,-1]);}
  const out={};for(const k in ev){const e=ev[k].sort((a,b)=>a[0]-b[0]||a[1]-b[1]);let c=0,m=0;for(const[,d]of e){c+=d;if(c>m)m=c;}out[k]=m;}
  console.log(`fExtB=${fExtB} rep=${rep} peak`,JSON.stringify(out));}
