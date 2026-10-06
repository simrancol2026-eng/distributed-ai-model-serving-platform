import { useEffect, useRef, useState } from 'react';
import type { Capability } from '@/types/platform';
import { capabilities } from '@/data/platform';
import { Button } from '@/components/ui/button';
import { RotateCcw } from 'lucide-react';
export function NetworkVisualization({ active, onSelect }: { active: Capability; onSelect: (value: Capability) => void }) {
 const mount = useRef<HTMLDivElement>(null);
 const selection = useRef(active);
 const resetView = useRef<(() => void) | null>(null);
 const [hovered, setHovered] = useState<string | null>(null);
 const [unavailable, setUnavailable] = useState(false);
 useEffect(() => { selection.current = active; }, [active]);
 useEffect(() => {
  const mountedHost = mount.current; if (!mountedHost) return;
  const host: HTMLDivElement = mountedHost;
  let dispose: (() => void) | undefined;
  let cancelled = false;
  import('three').then(THREE => {
   if (cancelled) return;
   const css = getComputedStyle(host);
   const color = (token: string) => {
    const probe = document.createElement('span'); probe.style.color = css.getPropertyValue(token); host.appendChild(probe);
    const value = getComputedStyle(probe).color; probe.remove();
    // Browser-normalize OKLCH tokens through a canvas to sRGB for WebGL.
    const canvas = document.createElement('canvas'); canvas.width=1; canvas.height=1;
    const context = canvas.getContext('2d');
    if (!context) return new THREE.Color();
    context.fillStyle=value; context.fillRect(0,0,1,1); const pixel=context.getImageData(0,0,1,1).data;
    return new THREE.Color((pixel[0] ?? 0)/255,(pixel[1] ?? 0)/255,(pixel[2] ?? 0)/255).convertSRGBToLinear();
   };
   const cyan=color('--primary'), amber=color('--accent'), dim=color('--network-dim');
   const scene = new THREE.Scene();
   const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true });
   renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2)); host.appendChild(renderer.domElement);
   renderer.domElement.setAttribute('aria-label','Interactive distributed NEXUS network');
   const camera=new THREE.PerspectiveCamera(34,1,0.1,100); camera.position.set(0,0,12); camera.lookAt(0,0,0);
   const root=new THREE.Group(); scene.add(root);
   scene.add(new THREE.AmbientLight(cyan,2)); const light=new THREE.DirectionalLight(cyan,5); light.position.set(2,5,4); scene.add(light);
   const core=new THREE.Group(); root.add(core);
   const materials: InstanceType<typeof THREE.Material>[]=[];
   const material = (value: InstanceType<typeof THREE.Color>, opacity=1) => { const m=new THREE.MeshBasicMaterial({ color: value, transparent: true, opacity }); materials.push(m); return m; };
   const lineMat = (value: InstanceType<typeof THREE.Color>, opacity=1) => { const m=new THREE.LineBasicMaterial({color:value,transparent:true,opacity}); materials.push(m); return m; };
   const outline = new THREE.Shape();
   const half=1.18, radius=0.16;
   outline.moveTo(-half+radius,-half);outline.lineTo(half-radius,-half);outline.quadraticCurveTo(half,-half,half,-half+radius);outline.lineTo(half,half-radius);outline.quadraticCurveTo(half,half,half-radius,half);outline.lineTo(-half+radius,half);outline.quadraticCurveTo(-half,half,-half,half-radius);outline.lineTo(-half,-half+radius);outline.quadraticCurveTo(-half,-half,-half+radius,-half);
   const coreGeometry=new THREE.ExtrudeGeometry(outline,{depth:0.14,bevelEnabled:false,curveSegments:12});
   const diamond=new THREE.Group(); diamond.rotation.z=Math.PI/4;core.add(diamond);
   diamond.add(new THREE.Mesh(coreGeometry,material(cyan,0.035)));
   diamond.add(new THREE.LineSegments(new THREE.EdgesGeometry(coreGeometry),lineMat(cyan,0.62)));
   const inner=new THREE.LineSegments(new THREE.EdgesGeometry(coreGeometry),lineMat(cyan,0.22));inner.scale.setScalar(0.83);diamond.add(inner);
   const rings: InstanceType<typeof THREE.Mesh>[]=[];
   for(let i=0;i<2;i++) {
    const ring=new THREE.Mesh(new THREE.TorusGeometry(2.26+i*0.30,0.006,4,160),material(cyan,i===0?0.15:0.09));core.add(ring);
    rings.push(ring);
   }
   const coords: [number,number,number][]=[[0,2.26,0],[2.26,0,0],[0,-2.26,0],[-2.26,0,0]];
   const ids: Capability[]=['TEXT','CODE','IMAGE','DOCUMENT'];
   const nodes: { mesh: InstanceType<typeof THREE.Mesh>; id: string; line: InstanceType<typeof THREE.Line>; point: InstanceType<typeof THREE.Mesh>; target: InstanceType<typeof THREE.Vector3> }[]=[];
   coords.forEach((coord,i)=> {
    const pos=new THREE.Vector3(...coord);
    const node=new THREE.Mesh(new THREE.BoxGeometry(0.15,0.15,0.15),material(cyan,0.85)); node.position.copy(pos); node.rotation.z=Math.PI/4; node.userData['capability']=ids[i]; root.add(node);
    const halo=new THREE.Mesh(new THREE.TorusGeometry(0.34,0.008,4,40),material(cyan,0.4)); halo.position.copy(pos);halo.rotation.x=0;root.add(halo);
    const points=[new THREE.Vector3(0,0,0),new THREE.Vector3(pos.x*0.7,pos.y*0.7,0),pos];
    const curve=new THREE.CatmullRomCurve3(points); const line=new THREE.Line(new THREE.BufferGeometry().setFromPoints(curve.getPoints(40)),lineMat(cyan,0.25));root.add(line);
    const particle=new THREE.Mesh(new THREE.SphereGeometry(0.035,8,8),material(amber,0.85));root.add(particle);
    nodes.push({mesh:node,id:ids[i] ?? 'AUTO',line,point:particle,target:pos});
    for(let j=0;j<3;j++) {
     const outward=pos.clone().normalize(); const tangent=new THREE.Vector3(-outward.y,outward.x,0); const worker=pos.clone().add(outward.multiplyScalar(0.38)).add(tangent.multiplyScalar((j-1)*0.24));
     const mesh=new THREE.Mesh(new THREE.OctahedronGeometry(0.072),material(cyan,0.42));mesh.position.copy(worker);root.add(mesh);
     root.add(new THREE.Line(new THREE.BufferGeometry().setFromPoints([pos,worker]),lineMat(cyan,0.16)));
    }
   });
   const dust=[]; for(let i=0;i<65;i++) { const angle=i*2.399;const radius=1.8+(i%9)*0.37; dust.push(Math.cos(angle)*radius,((i%7)-3)*0.15,Math.sin(angle)*radius); }
   const dustGeometry=new THREE.BufferGeometry();dustGeometry.setAttribute('position',new THREE.Float32BufferAttribute(dust,3));
   const dustMaterial=new THREE.PointsMaterial({color:cyan,size:0.018,transparent:true,opacity:0.25});root.add(new THREE.Points(dustGeometry,dustMaterial));
   const raycaster=new THREE.Raycaster(), pointer=new THREE.Vector2(); let mx=0,my=0,hoverId:string|null=null;
   function move(event: PointerEvent) {
    const rect=host.getBoundingClientRect();mx=((event.clientX-rect.left)/rect.width-0.5);my=((event.clientY-rect.top)/rect.height-0.5);
    pointer.set(mx*2,-my*2);raycaster.setFromCamera(pointer,camera);
    const hit=raycaster.intersectObjects(nodes.map(n=>n.mesh))[0]; const id=hit?.object.userData['capability']??null;
    if(id!==hoverId){hoverId=id;setHovered(id);} renderer.domElement.style.cursor=id?'pointer':'default';
   }
   function click(){if(hoverId)onSelect(hoverId as Capability);}
   function leave(){mx=0;my=0;hoverId=null;setHovered(null);}
   host.addEventListener('pointermove',move);host.addEventListener('click',click);host.addEventListener('pointerleave',leave);
   resetView.current=()=>{mx=0;my=0;root.rotation.set(0,0,0);};
   const resize=new ResizeObserver(()=>{const w=host.clientWidth,h=host.clientHeight;if(w&&h){renderer.setSize(w,h);camera.aspect=w/h;camera.position.z=w/h<1.1?11.8:10.5;camera.updateProjectionMatrix();}});resize.observe(host);
   const reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
   let frame=0;const clock=new THREE.Clock();
   function animate(){frame=requestAnimationFrame(animate);const t=reduced?0:clock.getElapsedTime();
    diamond.rotation.z=Math.PI/4+t*0.16;
    core.rotation.y=Math.sin(t*0.32)*0.22;core.rotation.x=reduced?0:Math.cos(t*0.26)*0.10;
    rings.forEach((ring,i)=>{ring.rotation.x=reduced?0:Math.sin(t*0.35+i*Math.PI)*0.38;ring.rotation.y=reduced?0:Math.cos(t*0.28+i*Math.PI)*0.28;});
    root.position.y=reduced?0:Math.sin(t*0.65)*0.055;
    root.rotation.y+=((reduced?0:mx*0.13)-root.rotation.y)*0.025;root.rotation.x+=((reduced?0:my*0.06)-root.rotation.x)*0.025;
    nodes.forEach((n,i)=>{n.mesh.rotation.y=t*0.5;n.mesh.rotation.x=t*0.25; const selected=n.id===selection.current||n.id===hoverId; (n.mesh.material as InstanceType<typeof THREE.MeshBasicMaterial>).opacity=selected?1:0.58;(n.line.material as InstanceType<typeof THREE.LineBasicMaterial>).opacity=selected?0.65:0.24; n.mesh.scale.setScalar((selected?1.25:1)*(reduced?1:1+Math.sin(t*1.2+i*1.5)*0.10));n.point.visible=false;});
    renderer.render(scene,camera);
   }animate();
   dispose=()=>{cancelAnimationFrame(frame);resize.disconnect();host.removeEventListener('pointermove',move);host.removeEventListener('click',click);host.removeEventListener('pointerleave',leave);scene.traverse(object=>{if(object instanceof THREE.Mesh||object instanceof THREE.Line||object instanceof THREE.Points){object.geometry.dispose();const m=object.material;if(Array.isArray(m))m.forEach(item=>item.dispose());else m.dispose();}});materials.forEach(m=>m.dispose());renderer.dispose();renderer.domElement.remove();};
  }).catch(()=>{if(!cancelled)setUnavailable(true);});
  return()=>{cancelled=true;dispose?.();};
 },[onSelect]);
 return <div className="network-experience">
  <div ref={mount} className="network-canvas" />
  <div className="network-core-label"><span>NEXUS</span><small>SYSTEM CORE</small></div>
  {capabilities.map((c,i)=><Button key={c.id} variant="ghost" className={`network-label network-label-${i} ${active===c.id?'selected':''}`} onClick={()=>onSelect(c.id)} onMouseEnter={()=>setHovered(c.id)} onMouseLeave={()=>setHovered(null)}><c.icon size={12}/>{c.id}<span className="label-dot"/></Button>)}
  {hovered&&<div className="network-tooltip" role="status"><strong>{hovered}</strong><span>Workers <b>—</b></span><span>Healthy <b>—</b></span><span>Busy <b>—</b></span><small>Not connected</small></div>}
  {unavailable&&<span className="network-fallback">NEXUS CORE · WebGL unavailable</span>}
  <div className="network-caption"><span className="tiny-dot"/> CONCEPTUAL NETWORK · NOT CONNECTED</div>
  <Button variant="ghost" size="icon" className="network-reset" title="Reset network view" aria-label="Reset network view" onClick={()=>resetView.current?.()}><RotateCcw/></Button>
 </div>;
}
