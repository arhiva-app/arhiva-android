/* Arhiva — Android omotač (Capacitor). Učitava se SAMO u Android aplikaciji, posle capacitor.js.
   Web Arhiva se ne menja: ovde se samo zamenjuje ono što WebView ne ume:
   1) <a download> (rezervna kopija, „Sačuvaj na telefon“) → fajl u Documents/Arhiva
   2) navigator.share sa fajlovima → Android „Podeli“
   3) service worker se ne registruje (aplikacija je već na telefonu) */
(function(){
  var C = window.Capacitor;
  if(!(C && C.isNativePlatform && C.isNativePlatform())) return;
  var reg = (window.capacitorExports && capacitorExports.registerPlugin) || C.registerPlugin;
  var Filesystem = reg('Filesystem'), Share = reg('Share');
  window.ARHIVA_NATIVE = true;

  if(navigator.serviceWorker){
    try{ Object.defineProperty(navigator.serviceWorker, 'register', { value: function(){ return Promise.resolve(); }, configurable: true }); }catch(e){}
  }

  function b64(blob){
    return new Promise(function(res, rej){
      var r = new FileReader();
      r.onload = function(){ var s = String(r.result); res(s.slice(s.indexOf(',') + 1)); };
      r.onerror = function(){ rej(r.error); };
      r.readAsDataURL(blob);
    });
  }
  function safe(n){ return String(n || 'fajl').replace(/[\\\/:*?"<>|\u0000-\u001f]+/g, '_').trim().slice(0, 150) || 'fajl'; }
  function en(){ try{ return localStorage.getItem('galerija:lang') === 'en'; }catch(e){ return false; } }
  function say(sr, eng){ var m = en() ? eng : sr; if(typeof window.toast === 'function') window.toast(m); }

  /* 1) preuzimanje → Documents/Arhiva/<ime> */
  async function saveDownload(href, name){
    try{
      var blob = await (await fetch(href)).blob();
      var fn = safe(name);
      await Filesystem.writeFile({ path: 'Arhiva/' + fn, data: await b64(blob), directory: 'DOCUMENTS', recursive: true });
      say('Sačuvano: Documents/Arhiva/' + fn, 'Saved: Documents/Arhiva/' + fn);
    }catch(e){
      console.error('arhiva-native save', e);
      say('Čuvanje nije uspelo: ' + (e && e.message || e), 'Saving failed: ' + (e && e.message || e));
    }
  }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    if(this.hasAttribute('download') && /^(blob:|data:)/.test(this.href || '')){
      saveDownload(this.href, this.getAttribute('download') || 'fajl');
      return;
    }
    return origClick.call(this);
  };

  /* 2) deljenje fajlova → Android „Podeli“ */
  navigator.canShare = function(d){ return !!(d && ((d.files && d.files.length) || d.text || d.url)); };
  navigator.share = async function(d){
    d = d || {};
    var uris = [];
    var list = d.files || [];
    for(var i = 0; i < list.length; i++){
      var f = list[i];
      var r = await Filesystem.writeFile({ path: 'deljenje/' + Date.now() + '-' + i + '/' + safe(f.name), data: await b64(f), directory: 'CACHE', recursive: true });
      uris.push(r.uri);
    }
    try{
      var o = {};
      if(d.title) o.title = d.title;
      if(d.text) o.text = d.text;
      if(d.url) o.url = d.url;
      if(uris.length) o.files = uris;
      await Share.share(o);
    }catch(e){
      var m = String(e && e.message || e);
      if(/cancel/i.test(m)){ var a = new Error(m); a.name = 'AbortError'; throw a; }
      throw e;
    }
  };
})();
