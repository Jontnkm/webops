// 데모 전용: 서버가 있어야 하는 기능(검사 실행·검색·삭제·정기 검사)은 누르면 안내만 띄운다
(() => {
  let toast, timer;
  function say(msg) {
    if (!toast) {
      toast = document.createElement('div');
      toast.className = 'demo-toast';
      toast.setAttribute('role', 'status');
      document.body.append(toast);
    }
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(timer);
    timer = setTimeout(() => toast.classList.remove('show'), 2600);
  }
  const MSG = '데모 화면에서는 이 기능을 쓸 수 없습니다. 실제 검사는 WebOps 서버에서 실행됩니다.';
  document.addEventListener('submit', (e) => { e.preventDefault(); say(MSG); }, true);
  document.addEventListener('click', (e) => {
    const a = e.target.closest('a[data-demo-off]');
    if (a) { e.preventDefault(); say(MSG); }
  }, true);
})();
