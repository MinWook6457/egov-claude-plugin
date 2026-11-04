/*
 * eGov Claude 채팅 화면. 자바 쪽(ChatTab)이 window.egov.* 를 호출하고, 화면 이벤트는 window.egovPost 로 넘긴다.
 * Eclipse 4.8 의 SWT Browser(IE11)에서도 돌도록 ES5 문법만 쓴다.
 */
window.egov = (function () {
  var log = document.getElementById('log');
  var composer = document.getElementById('composer');
  var input = document.getElementById('input');
  var attach = document.getElementById('attach');
  var mode = document.getElementById('mode');
  var projectSelect = document.getElementById('project');
  var PATH_RE = /^[\w.\-\/\\]+\.(java|xml|jsp|jspf|properties|js|ts|css|sql|md|gradle|html|yml|yaml|json|txt)(:\d+)?$/i;
  var textNodes = {};
  var toolNodes = {};
  var permNodes = {};

  function post(msg) {
    if (window.egovPost) window.egovPost(JSON.stringify(msg));
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function hasClass(e, cls) {
    return (' ' + e.className + ' ').indexOf(' ' + cls + ' ') >= 0;
  }

  function setClass(e, cls, on) {
    var has = hasClass(e, cls);
    if (on && !has) e.className = (e.className + ' ' + cls).replace(/^\s+/, '');
    if (!on && has) e.className = (' ' + e.className + ' ').replace(' ' + cls + ' ', ' ').replace(/^\s+|\s+$/g, '');
  }

  function closest(e, test) {
    while (e && e !== document) {
      if (e.nodeType === 1 && test(e)) return e;
      e = e.parentNode;
    }
    return null;
  }

  function trim(s) {
    return s.replace(/^\s+|\s+$/g, '');
  }

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** 입력창 높이가 바뀌면 대화 영역 아래 끝을 맞춘다. */
  function layout() {
    log.style.bottom = composer.offsetHeight + 'px';
  }

  function stickToBottom(fn) {
    var atBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
    fn();
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  function append(node) {
    var welcome = document.getElementById('welcome');
    if (welcome) welcome.parentNode.removeChild(welcome);
    stickToBottom(function () { log.appendChild(node); });
  }

  // ------------------------------------------------------------ 코드 색 구분

  var KEYWORDS = {
    java: 'abstract assert boolean break byte case catch char class continue default do double else enum extends final finally float for if implements import instanceof int interface long native new null package private protected public return short static super switch synchronized this throw throws transient try void volatile while true false var',
    js: 'var let const function return if else for while do switch case break continue new this typeof instanceof null undefined true false try catch finally throw class extends import export default',
    sql: 'select from where and or not insert into values update set delete join left right inner outer full cross on group by order having as case when then else end null is in like between distinct union all limit offset create table alter drop add column primary key foreign references index view sequence constraint default exists count sum max min avg substring substr concat coalesce nvl nvl2 decode to_char to_date lpad rpad trim upper lower cast varchar varchar2 char number numeric integer int bigint date timestamp text commit rollback asc desc with over partition row_number rownum sysdate now'
  };
  var KEYWORD_SETS = {};
  for (var k in KEYWORDS) {
    var set = {};
    var words = KEYWORDS[k].split(' ');
    for (var w = 0; w < words.length; w++) set[words[w]] = true;
    KEYWORD_SETS[k] = set;
  }

  function langOf(lang) {
    lang = (lang || '').toLowerCase().replace(/^language-/, '');
    if (/^(java|kt|groovy|scala|c|cpp|cs)$/.test(lang)) return 'java';
    if (/^(js|javascript|ts|typescript|json)$/.test(lang)) return 'js';
    if (/^(sql|plsql|mysql|pgsql|postgresql|oracle)$/.test(lang)) return 'sql';
    if (/^(xml|html|jsp|jspf|xhtml|svg|tld)$/.test(lang)) return 'xml';
    if (/^(properties|ini|conf)$/.test(lang)) return 'properties';
    return '';
  }

  /** 한 덩어리 텍스트를 색이 입혀진 HTML 로 바꾼다. 알 수 없는 언어면 이스케이프만 한다. */
  function highlight(text, lang) {
    lang = langOf(lang);
    if (!lang) return esc(text);
    if (lang === 'xml') return highlightXml(text);
    if (lang === 'properties') {
      return esc(text).replace(/^(\s*[#!].*)$/gm, '<span class="hl-c">$1</span>')
        .replace(/^(\s*[^#!=:\s][^=:]*)([=:])/gm, '<span class="hl-a">$1</span>$2');
    }
    var comment = lang === 'sql' ? '--[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/' : '\\/\\/[^\\n]*|\\/\\*[\\s\\S]*?\\*\\/';
    var re = new RegExp('(' + comment + ')|("(?:\\\\.|[^"\\\\\\n])*"|\'(?:\\\\.|[^\'\\\\\\n])*\')|(@[A-Za-z_]\\w*)|(\\b\\d+(?:\\.\\d+)?\\b)|([A-Za-z_]\\w*)', 'g');
    var set = KEYWORD_SETS[lang];
    var out = '';
    var last = 0;
    var m;
    while ((m = re.exec(text)) !== null) {
      out += esc(text.substring(last, m.index));
      last = re.lastIndex;
      if (m[1]) out += '<span class="hl-c">' + esc(m[1]) + '</span>';
      else if (m[2]) out += '<span class="hl-s">' + esc(m[2]) + '</span>';
      else if (m[3]) out += lang === 'java' ? '<span class="hl-n">' + esc(m[3]) + '</span>' : esc(m[3]);
      else if (m[4]) out += '<span class="hl-d">' + m[4] + '</span>';
      else out += set[lang === 'sql' ? m[5].toLowerCase() : m[5]] ? '<span class="hl-k">' + m[5] + '</span>' : m[5];
    }
    return out + esc(text.substring(last));
  }

  /** XML/JSP/MyBatis. 태그·속성·문자열을 칠하고, SQL 삽입 위험이 있는 ${} 는 경고색으로 표시한다. */
  function highlightXml(text) {
    var re = /(<!--[\s\S]*?-->)|(<\/?[\w:.\-]+)|("[^"]*"|'[^']*')|(\$\{[^}]*\})|(#\{[^}]*\})|(\/?>)/g;
    var out = '';
    var last = 0;
    var m;
    while ((m = re.exec(text)) !== null) {
      out += esc(text.substring(last, m.index));
      last = re.lastIndex;
      if (m[1]) out += '<span class="hl-c">' + esc(m[1]) + '</span>';
      else if (m[2]) out += '<span class="hl-t">' + esc(m[2]) + '</span>';
      else if (m[3]) out += '<span class="hl-s">' + esc(m[3]).replace(/\$\{[^}]*\}/g, '<span class="hl-warn" title="SQL 삽입·XSS 위험 여부를 확인하세요">$&</span>') + '</span>';
      else if (m[4]) out += '<span class="hl-warn" title="SQL 삽입·XSS 위험 여부를 확인하세요">' + esc(m[4]) + '</span>';
      else if (m[5]) out += '<span class="hl-n">' + esc(m[5]) + '</span>';
      else out += '<span class="hl-t">' + esc(m[6]) + '</span>';
    }
    return out + esc(text.substring(last));
  }

  /** 마크다운 결과를 다듬는다: 코드 블록 색 구분, 파일 경로 칩 클릭, '주의/중요' 문단 강조. */
  function decorate(root) {
    var codes = root.getElementsByTagName('code');
    for (var i = 0; i < codes.length; i++) {
      var c = codes[i];
      if (c.parentNode.tagName === 'PRE') {
        if (!c.getAttribute('data-hl')) {
          c.setAttribute('data-hl', '1');
          c.innerHTML = highlight(c.textContent, c.className);
        }
      } else if (PATH_RE.test(trim(c.textContent))) {
        setClass(c, 'path', true);
        c.title = '클릭하면 에디터에서 엽니다';
      }
    }
    var tables = root.getElementsByTagName('table');
    for (var t = 0; t < tables.length; t++) {
      var tb = tables[t];
      if (tb.parentNode.className !== 'table-wrap') {
        var wrap = el('div', 'table-wrap');
        tb.parentNode.insertBefore(wrap, tb);
        wrap.appendChild(tb);
      }
    }
    var paras = root.getElementsByTagName('p');
    for (var j = 0; j < paras.length; j++) {
      var first = paras[j].firstChild;
      if (first && first.nodeType === 1 && first.tagName === 'STRONG' && /^(주의|중요|경고|확인 필요|위험)/.test(first.textContent)) {
        setClass(paras[j], 'callout', true);
      }
    }
  }

  // ------------------------------------------------------------ 표시 블록

  function preWithText(text, lang) {
    var pre = el('pre');
    var code = el('code');
    code.innerHTML = highlight(text, lang);
    pre.appendChild(code);
    return pre;
  }

  /** 줄 번호가 붙은 diff 표. rows: [{k:' '|'+'|'-'|'~', o:이전줄, n:새줄, s:내용}] */
  function diffTable(rows, lang) {
    var wrap = el('div', 'diff-wrap');
    var table = el('table', 'diff');
    var tbody = el('tbody');
    for (var i = 0; i < rows.length; i++) {
      var r = rows[i];
      var tr = el('tr', r.k === '+' ? 'add' : r.k === '-' ? 'del' : r.k === '~' ? 'gap' : 'ctx');
      if (r.k === '~') {
        var gap = el('td', null, r.s || '⋯');
        gap.colSpan = 4;
        tr.appendChild(gap);
      } else {
        tr.appendChild(el('td', 'ln', r.o == null ? '' : String(r.o)));
        tr.appendChild(el('td', 'ln', r.n == null ? '' : String(r.n)));
        tr.appendChild(el('td', 'mk', r.k === ' ' ? '' : r.k));
        var code = el('td', 'code');
        code.innerHTML = highlight(r.s, lang) || '&nbsp;';
        tr.appendChild(code);
      }
      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
  }

  function counts(added, removed) {
    var span = el('span', 'counts');
    if (added) span.appendChild(el('span', 'plus', '+' + added));
    if (removed) span.appendChild(el('span', 'minus', '−' + removed));
    return span;
  }

  /** 접었다 펼 수 있는 상자. head 를 누르면 .fold 가 보였다 숨었다 한다. */
  function foldable(box, head, collapsed) {
    setClass(box, 'collapsed', collapsed);
    head.onclick = function (e) {
      var t = e.target || e.srcElement;
      if (closest(t, function (n) { return n.getAttribute('data-open') != null || n.tagName === 'BUTTON'; })) return;
      setClass(box, 'collapsed', !hasClass(box, 'collapsed'));
    };
  }

  function permissionBar(id, canAlways) {
    var actions = el('div', 'perm-actions');
    actions.appendChild(el('span', 'perm-q', '이 작업을 허용할까요?'));
    function btn(label, decision, cls) {
      var b = el('button', cls, label);
      b.onclick = function () { post({ type: 'permission', id: id, decision: decision }); };
      actions.appendChild(b);
    }
    btn('허용', 'allow');
    if (canAlways) btn('이 세션 동안 계속 허용', 'always', 'ghost');
    btn('거부', 'deny', 'danger');
    return actions;
  }

  function send() {
    var text = trim(input.value);
    if (!text) return;
    post({ type: 'send', text: text, attach: attach.checked });
    input.value = '';
  }

  // ------------------------------------------------------------ 화면 이벤트
  input.onkeydown = function (e) {
    // 한글 조합 중 Enter(keyCode 229)는 글자 확정용이므로 전송하지 않는다.
    if (e.keyCode === 13 && !e.shiftKey && !e.isComposing) {
      if (e.preventDefault) e.preventDefault();
      send();
      return false;
    }
  };
  document.getElementById('btn-send').onclick = send;
  document.getElementById('btn-stop').onclick = function () { post({ type: 'stop' }); };
  mode.onchange = function () { post({ type: 'mode', mode: mode.value }); };
  projectSelect.onchange = function () { post({ type: 'project', name: projectSelect.value }); };
  // 목록이 비어 있거나 오래됐을 수 있으니 펼칠 때마다 새로 받는다.
  projectSelect.onmousedown = function () { if (projectSelect.options.length <= 1) post({ type: 'projects' }); };
  attach.onclick = function () { post({ type: 'attachDefault', value: attach.checked }); };
  var chips = document.querySelectorAll('[data-quick]');
  for (var i = 0; i < chips.length; i++) {
    chips[i].onclick = (function (action) {
      return function () { post({ type: 'quick', action: action }); };
    })(chips[i].getAttribute('data-quick'));
  }
  window.onresize = layout;

  log.onclick = function (e) {
    var target = e.target || e.srcElement;
    var a = closest(target, function (n) { return n.tagName === 'A'; });
    if (a) {
      var href = a.getAttribute('href') || '';
      if (/^https?:/i.test(href)) post({ type: 'link', url: href });
      else if (href) post({ type: 'open', path: decodeURIComponent(href) });
      return false;
    }
    var cmp = closest(target, function (n) { return n.getAttribute('data-compare') != null || n.getAttribute('data-compare-file') != null; });
    if (cmp) {
      if (cmp.getAttribute('data-compare') != null) post({ type: 'compare', id: cmp.getAttribute('data-compare') });
      else post({ type: 'compareFile', path: cmp.getAttribute('data-compare-file') });
      return false;
    }
    var open = closest(target, function (n) { return n.getAttribute('data-open') != null || hasClass(n, 'path'); });
    if (open) {
      post({ type: 'open', path: open.getAttribute('data-open') || trim(open.textContent) });
      return false;
    }
  };

  // ------------------------------------------------------------ 자바에서 호출
  return {
    init: function (modeId, attachDefault) {
      mode.value = modeId;
      attach.checked = attachDefault;
      layout();
      input.focus();
    },

    setProjects: function (names, selected) {
      projectSelect.innerHTML = '';
      if (!names.length) projectSelect.appendChild(el('option', null, '(열린 프로젝트 없음)'));
      for (var i = 0; i < names.length; i++) {
        var o = el('option', null, names[i]);
        o.value = names[i];
        if (names[i] === selected) o.selected = true;
        projectSelect.appendChild(o);
      }
    },

    setSession: function (model) {
      document.getElementById('session').textContent = model ? '● ' + model : '● 연결됨';
    },

    setBusy: function (busy) {
      setClass(document.getElementById('busy'), 'hidden', !busy);
      setClass(document.getElementById('btn-stop'), 'hidden', !busy);
    },

    addUser: function (label, fullPrompt) {
      var m = el('div', 'msg user', label);
      if (fullPrompt) {
        var toggle = el('div', 'toggle', '▸ 보낸 요청 전체 보기');
        var body = el('div', 'fold');
        body.appendChild(el('pre', null, fullPrompt));
        m.appendChild(toggle);
        m.appendChild(body);
        foldable(m, toggle, true);
      }
      append(m);
    },

    upsertText: function (key, html) {
      var m = textNodes[key];
      stickToBottom(function () {
        if (!m) {
          m = textNodes[key] = el('div', 'msg assistant');
          append(m);
        }
        m.innerHTML = html;
        decorate(m);
      });
    },

    /** 읽기·검색·명령 같은 일반 도구. 기본으로 접혀 있다. */
    addTool: function (id, name, summary, openPath, detail, lang) {
      var box = el('div', 'tool');
      var head = el('div', 'head');
      var state = el('span', 'state muted', '실행 중…');
      head.appendChild(state);
      head.appendChild(el('span', 'name', name));
      var sum = el('span', 'sum', summary || '');
      if (openPath) {
        sum.setAttribute('data-open', openPath);
        sum.title = '클릭하면 에디터에서 엽니다';
        setClass(sum, 'link', true);
      }
      head.appendChild(sum);
      box.appendChild(head);
      var body = el('div', 'fold');
      if (detail) body.appendChild(preWithText(detail, lang));
      box.appendChild(body);
      foldable(box, head, true);
      toolNodes[id] = { box: box, state: state, body: body, head: head };
      append(box);
    },

    /** 파일 수정. 어떤 파일의 몇 번째 줄이 어떻게 바뀌는지 펼친 상태로 보여 준다. */
    addEdit: function (id, tool, path, openPath, added, removed, rows, created, lang) {
      var box = el('div', 'tool edit');
      var head = el('div', 'head');
      var state = el('span', 'state muted', '실행 중…');
      head.appendChild(state);
      head.appendChild(el('span', 'badge ' + (created ? 'new' : 'mod'), created ? '새 파일' : tool === 'Write' ? '덮어쓰기' : '수정'));
      var file = el('span', 'sum link file', path);
      file.setAttribute('data-open', openPath);
      file.title = '클릭하면 바뀐 줄로 이동합니다';
      head.appendChild(file);
      head.appendChild(counts(added, removed));
      var cmp = el('button', 'ghost mini', '비교');
      cmp.setAttribute('data-compare', id);
      cmp.title = 'Eclipse 비교 편집기에서 변경 전/후를 나란히 봅니다';
      head.appendChild(cmp);
      box.appendChild(head);
      var body = el('div', 'fold');
      body.appendChild(rows && rows.length ? diffTable(rows, lang) : el('div', 'muted', '바뀐 내용이 없습니다.'));
      box.appendChild(body);
      foldable(box, head, false);
      toolNodes[id] = { box: box, state: state, body: body, head: head };
      append(box);
    },

    toolResult: function (id, content, isError) {
      var t = toolNodes[id];
      if (!t) return;
      t.state.className = 'state ' + (isError ? 'err' : 'ok');
      t.state.textContent = isError ? '실패' : '완료';
      if (content) {
        t.body.appendChild(el('div', 'label', isError ? '오류' : '결과'));
        t.body.appendChild(el('pre', null, content));
      }
      if (isError) setClass(t.box, 'collapsed', false);
    },

    /** 권한 요청. 해당 도구 카드가 있으면 그 안에 버튼을 붙이고, 없으면 따로 카드를 만든다. */
    addPermission: function (id, toolUseId, tool, summary, detail, rows, canAlways, lang) {
      var t = toolUseId ? toolNodes[toolUseId] : null;
      var bar = permissionBar(id, canAlways);
      if (t) {
        setClass(t.box, 'collapsed', false);
        setClass(t.box, 'pending', true);
        t.state.textContent = '승인 대기';
        t.box.appendChild(bar);
        permNodes[id] = { box: t.box, actions: bar };
        // 승인할 내용이 화면 밖에 있으면 놓치기 쉬우므로 항상 아래로 내린다.
        log.scrollTop = log.scrollHeight;
        return;
      }
      var box = el('div', 'tool pending');
      var head = el('div', 'head');
      head.appendChild(el('span', 'state muted', '승인 대기'));
      head.appendChild(el('span', 'name', tool));
      head.appendChild(el('span', 'sum', summary || ''));
      box.appendChild(head);
      var body = el('div', 'fold');
      if (rows && rows.length) body.appendChild(diffTable(rows, lang));
      else if (detail) body.appendChild(preWithText(detail, lang));
      box.appendChild(body);
      box.appendChild(bar);
      permNodes[id] = { box: box, actions: bar };
      append(box);
    },

    resolvePermission: function (id, label, allowed) {
      var p = permNodes[id];
      if (!p) return;
      setClass(p.box, 'pending', false);
      if (!allowed) setClass(p.box, 'denied', true);
      p.box.replaceChild(el('div', 'perm-done ' + (allowed ? 'ok' : 'err'), label), p.actions);
    },

    /** 이번 요청에서 바뀐 파일 요약. 파일을 누르면 바뀐 줄로, [비교] 를 누르면 비교 편집기로 간다. */
    addChangeSummary: function (files) {
      var box = el('div', 'changes');
      var added = 0;
      var removed = 0;
      for (var i = 0; i < files.length; i++) {
        added += files[i].added;
        removed += files[i].removed;
      }
      var head = el('div', 'changes-head');
      head.appendChild(el('span', 'title', '변경된 파일 ' + files.length + '개'));
      head.appendChild(counts(added, removed));
      box.appendChild(head);
      for (var j = 0; j < files.length; j++) {
        var f = files[j];
        var row = el('div', 'change-row');
        row.appendChild(el('span', 'badge ' + (f.created ? 'new' : 'mod'), f.created ? '새 파일' : '수정'));
        var name = el('span', 'cname', f.name);
        name.setAttribute('data-open', f.open);
        name.title = '클릭하면 바뀐 줄로 이동합니다';
        row.appendChild(name);
        if (f.dir) row.appendChild(el('span', 'cdir', f.dir));
        row.appendChild(counts(f.added, f.removed));
        var cmp = el('button', 'ghost mini', '비교');
        cmp.setAttribute('data-compare-file', f.path);
        row.appendChild(cmp);
        box.appendChild(row);
      }
      append(box);
    },

    addResult: function (isError, message, meta) {
      if (isError && message) append(el('div', 'notice error', message));
      append(el('div', 'result' + (isError ? ' error' : ''), (isError ? '실패 · ' : '완료 · ') + meta));
    },

    addSystem: function (kind, text) {
      append(el('div', 'notice ' + kind, text));
    }
  };
})();
