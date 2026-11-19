/*
 * LifeOS visual-direction mockups.
 * One content model, one EN/TR string table, three direction skins.
 * Everything shown is invented placeholder content. No network, no build step.
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------ */
  /* Strings                                                             */
  /* ------------------------------------------------------------------ */

  var STRINGS = {
    en: {
      pageTitle: "LifeOS app — visual directions",
      pageNote: "Static mockup. All content is invented placeholder data.",
      ctlView: "View", viewSingle: "One direction", viewCompare: "Compare all",
      ctlDir: "Direction", ctlScreen: "Screen", ctlTheme: "Theme", ctlLang: "Language",
      light: "Light", dark: "Dark",
      dirName: { A: "Calm paper", B: "Material You", C: "Dark console" },
      dirBlurb: {
        A: "A quiet notebook: warm paper, serif titles, hairline cards, one deep-teal accent.",
        B: "Native and familiar: tonal surfaces and standard Material 3 shapes. Colours would follow the phone's wallpaper; one example palette is shown.",
        C: "A focused tool: near-black, high contrast, monospace headings and numbers, one amber accent. Dark is its primary look."
      },
      today: "Today", chat: "Chat", tasks: "Tasks", more: "More", activity: "Activity",
      capture: "Capture a note or task", back: "Back", nav: "Main navigation",
      connected: "Connected", connectedLong: "Connected to the computer",
      greeting: "Good morning",
      secEvents: "Next events", eventsMeta: "2 today",
      ev1Title: "Example course", ev1Sub: "Lecture · Room 101",
      ev2Title: "Project Alpha check-in", ev2Sub: "Video call",
      secTasks: "Tasks", tasksMeta: "1 overdue",
      task1: "Submit Example assignment 1", task1Sub: "Yesterday · Example course",
      task2: "Draft the Project Alpha outline", task2Sub: "Today",
      task3: "Buy printer paper", task3Sub: "Done",
      overdue: "Overdue",
      secDeadlines: "Upcoming deadlines", deadlinesMeta: "next 7 days",
      dl1: "Example assignment 2", dl1Course: "Example course",
      dl2: "Example quiz", dl2Course: "Example seminar",
      inDays: function (n) { return "in " + n + " days"; },
      tileSleep: "Last night", tileScreen: "Screen time",
      tileScreenSub: function (n) { return n + " unlocks"; },
      newChat: "New chat", you: "You", assistant: "LifeOS",
      chatQ1: "What is still open for Project Alpha this week?",
      chatA1: "Three things are still open:",
      chatLi1: "Draft the outline — due today",
      chatLi2: "Review the check-in notes",
      chatLi3: "Write the milestone summary by Tuesday",
      chatA2: "The notes are in the project folder:",
      codeLabel: "shell",
      chatQ2: "Add the summary as a task for Monday.",
      working: "Working…", cancel: "Cancel",
      composer: "Message LifeOS", send: "Send",
      tabSleep: "Sleep", tabScreen: "Screen time",
      lastNight: "Last night", manual: "entered by hand",
      bed: "Bed time", wake: "Wake time", duration: "Duration",
      sleepChart: "Sleep duration", days7: "7 days", days30: "30 days",
      estimated: "estimated", estShort: "est.", average: "average", avgShort: "avg",
      sleepSummary: function (avg, lo, loDur, hi, hiDur) {
        return "7-night average " + avg + ". Shortest " + lo + " (" + loDur +
          "), longest " + hi + " (" + hiDur + ", estimated).";
      },
      screenToday: "Screen time today",
      unlocks: function (n) { return n + " unlocks"; },
      topApps: "Top apps",
      apps: ["Browser", "Messages", "Video", "Maps"],
      appsSummary: function (list) { return "Top apps today: " + list + "."; },
      unitH: "h", unitMin: "min"
    },
    tr: {
      pageTitle: "LifeOS uygulaması — görsel yönler",
      pageNote: "Durağan taslak. Tüm içerik uydurma örnek veridir.",
      ctlView: "Görünüm", viewSingle: "Tek yön", viewCompare: "Hepsini karşılaştır",
      ctlDir: "Yön", ctlScreen: "Ekran", ctlTheme: "Tema", ctlLang: "Dil",
      light: "Açık", dark: "Koyu",
      dirName: { A: "Sakin kâğıt", B: "Material You", C: "Koyu konsol" },
      dirBlurb: {
        A: "Sessiz bir defter: sıcak kâğıt tonu, serif başlıklar, ince çizgili kartlar, tek bir koyu turkuaz vurgu.",
        B: "Tanıdık ve yerel: tonlu yüzeyler ve standart Material 3 biçimleri. Renkler telefonun duvar kâğıdından gelir; burada örnek bir palet gösteriliyor.",
        C: "Odaklı bir araç: siyaha yakın zemin, yüksek kontrast, eş aralıklı başlık ve rakamlar, tek bir kehribar vurgu. Asıl görünümü koyu temadır."
      },
      today: "Bugün", chat: "Sohbet", tasks: "Görevler", more: "Diğer", activity: "Aktivite",
      capture: "Not ya da görev ekle", back: "Geri", nav: "Ana gezinme",
      connected: "Bağlı", connectedLong: "Bilgisayara bağlı",
      greeting: "Günaydın",
      secEvents: "Sıradaki etkinlikler", eventsMeta: "bugün 2",
      ev1Title: "Örnek ders", ev1Sub: "Ders · Derslik 101",
      ev2Title: "Alfa Projesi görüşmesi", ev2Sub: "Görüntülü görüşme",
      secTasks: "Görevler", tasksMeta: "1 gecikmiş",
      task1: "Örnek ödev 1'i teslim et", task1Sub: "Dün · Örnek ders",
      task2: "Alfa Projesi taslağını hazırla", task2Sub: "Bugün",
      task3: "Yazıcı kâğıdı al", task3Sub: "Tamamlandı",
      overdue: "Gecikti",
      secDeadlines: "Yaklaşan teslimler", deadlinesMeta: "sonraki 7 gün",
      dl1: "Örnek ödev 2", dl1Course: "Örnek ders",
      dl2: "Örnek sınav", dl2Course: "Örnek seminer",
      inDays: function (n) { return n + " gün sonra"; },
      tileSleep: "Dün gece", tileScreen: "Ekran süresi",
      tileScreenSub: function (n) { return n + " kilit açma"; },
      newChat: "Yeni sohbet", you: "Sen", assistant: "LifeOS",
      chatQ1: "Alfa Projesi için bu hafta neler kaldı?",
      chatA1: "Hâlâ açık üç iş var:",
      chatLi1: "Taslağı hazırla — son gün bugün",
      chatLi2: "Görüşme notlarını gözden geçir",
      chatLi3: "Salıya kadar aşama özetini yaz",
      chatA2: "Notlar proje klasöründe:",
      codeLabel: "kabuk",
      chatQ2: "Özeti pazartesiye görev olarak ekle.",
      working: "Çalışıyor…", cancel: "İptal",
      composer: "LifeOS'a yaz", send: "Gönder",
      tabSleep: "Uyku", tabScreen: "Ekran süresi",
      lastNight: "Dün gece", manual: "elle girildi",
      bed: "Yatış", wake: "Kalkış", duration: "Süre",
      sleepChart: "Uyku süresi", days7: "7 gün", days30: "30 gün",
      estimated: "tahmini", estShort: "tah.", average: "ortalama", avgShort: "ort.",
      sleepSummary: function (avg, lo, loDur, hi, hiDur) {
        return "7 gecelik ortalama " + avg + ". En kısa " + lo + " (" + loDur +
          "), en uzun " + hi + " (" + hiDur + ", tahmini).";
      },
      screenToday: "Bugünkü ekran süresi",
      unlocks: function (n) { return n + " kilit açma"; },
      topApps: "En çok kullanılanlar",
      apps: ["Tarayıcı", "Mesajlar", "Video", "Haritalar"],
      appsSummary: function (list) { return "Bugün en çok kullanılanlar: " + list + "."; },
      unitH: "sa", unitMin: "dk"
    }
  };

  /* ------------------------------------------------------------------ */
  /* Placeholder data (identical for every direction)                    */
  /* ------------------------------------------------------------------ */

  var LOCALE = { en: "en-GB", tr: "tr-TR" };
  var TODAY = new Date(2026, 9, 14); // fixed, invented "today": a Wednesday

  var DATA = {
    events: [
      { start: "10:30", end: "12:00", title: "ev1Title", sub: "ev1Sub" },
      { start: "15:00", end: "15:30", title: "ev2Title", sub: "ev2Sub" }
    ],
    tasks: [
      { title: "task1", sub: "task1Sub", overdue: true, done: false },
      { title: "task2", sub: "task2Sub", overdue: false, done: false },
      { title: "task3", sub: "task3Sub", overdue: false, done: true }
    ],
    deadlines: [
      { inDays: 2, title: "dl1", course: "dl1Course" },
      { inDays: 5, title: "dl2", course: "dl2Course" }
    ],
    lastNight: { bed: "23:40", wake: "07:05", minutes: 445 },
    // Seven nights ending last night, oldest first. Minutes asleep.
    sleep: [
      { minutes: 426, est: false },
      { minutes: 384, est: false },
      { minutes: 498, est: true },
      { minutes: 468, est: true },
      { minutes: 414, est: false },
      { minutes: 372, est: false },
      { minutes: 445, est: false }
    ],
    screenMinutes: 192,
    unlocks: 41,
    appMinutes: [58, 47, 39, 18]
  };

  /* ------------------------------------------------------------------ */
  /* State and URL handling                                              */
  /* ------------------------------------------------------------------ */

  var OPTIONS = {
    view: ["single", "compare"],
    dir: ["A", "B", "C"],
    screen: ["today", "chat", "activity"],
    theme: ["light", "dark"],
    lang: ["en", "tr"]
  };
  var state = { view: "single", dir: "A", screen: "today", theme: "light", lang: "en" };

  function readUrl() {
    var params;
    try { params = new URLSearchParams(window.location.search); } catch (e) { return; }
    var given = {};
    params.forEach(function (value, key) { given[key.toLowerCase()] = value; });
    Object.keys(OPTIONS).forEach(function (key) {
      var raw = given[key];
      if (!raw) { return; }
      var match = OPTIONS[key].filter(function (opt) {
        return opt.toLowerCase() === raw.toLowerCase();
      })[0];
      if (match) { state[key] = match; }
    });
  }

  function writeUrl() {
    var query = Object.keys(OPTIONS).map(function (key) {
      return key + "=" + state[key];
    }).join("&");
    try { window.history.replaceState(null, "", "?" + query); } catch (e) { /* file:// may refuse */ }
  }

  /* ------------------------------------------------------------------ */
  /* Formatting helpers                                                  */
  /* ------------------------------------------------------------------ */

  var uid = 0;
  function nextId(prefix) { uid += 1; return prefix + "-" + uid; }

  function esc(text) {
    return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  function addDays(date, n) {
    var d = new Date(date.getTime());
    d.setDate(d.getDate() + n);
    return d;
  }

  function fmtDate(date, opts, lang) {
    return new Intl.DateTimeFormat(LOCALE[lang], opts).format(date);
  }

  function fmtNum(value, lang, digits) {
    return new Intl.NumberFormat(LOCALE[lang], {
      minimumFractionDigits: digits, maximumFractionDigits: digits
    }).format(value);
  }

  /** Duration as plain text, e.g. "7 h 25 min" / "7 sa 25 dk". */
  function durText(minutes, lang) {
    var s = STRINGS[lang];
    var h = Math.floor(minutes / 60);
    var m = minutes % 60;
    var parts = [];
    if (h) { parts.push(h + " " + s.unitH); }
    if (m || !h) { parts.push(m + " " + s.unitMin); }
    return parts.join(" ");
  }

  /** Duration with number/unit spans so each direction can style them. */
  function durHtml(minutes, lang) {
    var s = STRINGS[lang];
    var h = Math.floor(minutes / 60);
    var m = minutes % 60;
    var out = "";
    if (h) { out += '<span class="num">' + h + '</span><span class="unit">' + s.unitH + "</span>"; }
    if (m || !h) { out += '<span class="num">' + m + '</span><span class="unit">' + s.unitMin + "</span>"; }
    return '<span class="dur">' + out + "</span>";
  }

  /* ------------------------------------------------------------------ */
  /* Icons (inline SVG, 24px grid)                                       */
  /* ------------------------------------------------------------------ */

  var ICON_PATHS = {
    today: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
    chat: '<path d="M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-5 4v-4H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"/>',
    tasks: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 12.2l2.8 2.8L16 9.2"/>',
    more: '<circle cx="5.5" cy="12" r="1.4" class="solid"/><circle cx="12" cy="12" r="1.4" class="solid"/><circle cx="18.5" cy="12" r="1.4" class="solid"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    plane: '<path class="solid" d="M3.5 20.5l18-8.5-18-8.5v6.6l11 1.9-11 1.9z"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    compose: '<path d="M12 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5V12"/><path d="M17.6 3.6l2.8 2.8-8 8-3.4.6.6-3.4z"/>',
    moon: '<path d="M20 14.2A8 8 0 1 1 9.8 4a6.4 6.4 0 0 0 10.2 10.2z"/>',
    phone: '<rect x="7" y="3" width="10" height="18" rx="2"/><path d="M11 17.5h2"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>'
  };

  function icon(name, extraClass) {
    return '<svg class="icon ' + (extraClass || "") + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
      ICON_PATHS[name] + "</svg>";
  }

  /* ------------------------------------------------------------------ */
  /* Shared phone parts                                                  */
  /* ------------------------------------------------------------------ */

  function statusBar() {
    return '<div class="statusbar" aria-hidden="true"><span class="sb-time">09:41</span><span class="sb-cam"></span>' +
      '<span class="sb-icons"><svg viewBox="0 0 40 12" width="40" height="12">' +
      '<path d="M1 11h2V7H1zM5 11h2V5H5zM9 11h2V3H9zM13 11h2V1h-2z" fill="currentColor"/>' +
      '<rect x="21" y="1.5" width="15" height="9" rx="2" fill="none" stroke="currentColor"/>' +
      '<rect x="23" y="3.5" width="9" height="5" rx="0.5" fill="currentColor"/>' +
      '<rect x="37" y="4" width="1.6" height="4" rx="0.5" fill="currentColor"/></svg></span></div>';
  }

  function topBar(dir, s, title, opts) {
    opts = opts || {};
    var html = '<header class="topbar">';
    if (opts.back) {
      html += '<span class="tb-btn tb-back" role="button" aria-label="' + esc(s.back) + '">' + icon("back") + "</span>";
    }
    html += '<h2 class="tb-title">' + (dir === "C" ? '<span class="prompt" aria-hidden="true">&gt;</span>' : "") +
      esc(title) + "</h2>";
    html += '<span class="tb-spacer"></span>';
    if (opts.newChat) {
      html += '<span class="tb-action" role="button">' + icon(dir === "C" ? "plus" : "compose") +
        '<span class="tb-action-lbl">' + esc(s.newChat) + "</span></span>";
    }
    html += '<span class="status" role="button" title="' + esc(s.connectedLong) + '">' +
      '<span class="status-dot" aria-hidden="true"></span>' +
      '<span class="status-lbl">' + esc(s.connected) + "</span></span>";
    return html + "</header>";
  }

  function bottomBar(dir, s, current) {
    var items = ["today", "chat", "tasks", "more"];
    var html = '<nav class="bottombar" aria-label="' + esc(s.nav) + '">';
    items.forEach(function (key) {
      var on = key === current;
      html += '<span class="nav-item' + (on ? " on" : "") + '"' + (on ? ' aria-current="page"' : "") + ">" +
        '<span class="nav-ind">' + icon(key, on ? "is-on" : "") + "</span>" +
        '<span class="nav-lbl">' + esc(s[key]) + "</span></span>";
    });
    return html + '<span class="gesture" aria-hidden="true"></span></nav>';
  }

  function fab(s) {
    return '<span class="fab" role="button" aria-label="' + esc(s.capture) + '">' + icon("plus") + "</span>";
  }

  function section(label, meta, body, extraClass) {
    return '<section class="sec ' + (extraClass || "") + '"><div class="sec-head"><h3>' + esc(label) + "</h3>" +
      '<span class="sec-rule" aria-hidden="true"></span>' +
      (meta ? '<span class="sec-meta">' + meta + "</span>" : "") + "</div>" + body + "</section>";
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Today                                                       */
  /* ------------------------------------------------------------------ */

  function screenToday(dir, lang) {
    var s = STRINGS[lang];
    var dateLong = fmtDate(TODAY, { weekday: "long", day: "numeric", month: "long" }, lang);

    var events = DATA.events.map(function (ev) {
      return '<div class="row event"><div class="lead lead-time"><b>' + ev.start + "</b><span>" + ev.end + "</span></div>" +
        '<div class="main"><div class="row-t">' + esc(s[ev.title]) + '</div><div class="row-s">' + esc(s[ev.sub]) + "</div></div></div>";
    }).join("");

    var tasks = DATA.tasks.map(function (t) {
      var cls = "row task" + (t.done ? " done" : "") + (t.overdue ? " overdue" : "");
      return '<div class="' + cls + '"><span class="check" role="checkbox" aria-checked="' + (t.done ? "true" : "false") +
        '" aria-label="' + esc(s[t.title]) + '">' + (t.done ? icon("check") : "") + "</span>" +
        '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s">' +
        (t.overdue ? '<span class="tag tag-alert"><span class="tag-mark" aria-hidden="true">!</span>' + esc(s.overdue) + "</span>" : "") +
        "<span>" + esc(s[t.sub]) + "</span></div></div></div>";
    }).join("");

    var deadlines = DATA.deadlines.map(function (d) {
      var date = addDays(TODAY, d.inDays);
      return '<div class="row deadline"><div class="lead lead-date"><b>' + fmtDate(date, { day: "numeric" }, lang) +
        "</b><span>" + esc(fmtDate(date, { month: "short" }, lang)) + "</span></div>" +
        '<div class="main"><div class="row-t">' + esc(s[d.title]) + '</div><div class="row-s"><span>' +
        esc(fmtDate(date, { weekday: "long" }, lang)) + " · " + esc(s[d.course]) + "</span></div></div>" +
        '<span class="when">' + esc(s.inDays(d.inDays)) + "</span></div>";
    }).join("");

    var tiles = '<div class="tiles">' +
      '<div class="tile tile-sleep" role="link"><div class="tile-lbl">' + icon("moon") + "<span>" + esc(s.tileSleep) + "</span></div>" +
      '<div class="tile-val">' + durHtml(DATA.lastNight.minutes, lang) + "</div>" +
      '<div class="tile-sub">' + DATA.lastNight.bed + " – " + DATA.lastNight.wake + "</div></div>" +
      '<div class="tile tile-screen" role="link"><div class="tile-lbl">' + icon("phone") + "<span>" + esc(s.tileScreen) + "</span></div>" +
      '<div class="tile-val">' + durHtml(DATA.screenMinutes, lang) + "</div>" +
      '<div class="tile-sub">' + esc(s.tileScreenSub(DATA.unlocks)) + "</div></div></div>";

    return topBar(dir, s, s.today) +
      '<div class="content content-today">' +
      '<div class="greeting"><h1>' + esc(s.greeting) + (dir === "C" ? '<span class="cursor" aria-hidden="true">_</span>' : "") +
      '</h1><p class="greet-date">' + esc(dateLong) + "</p></div>" +
      section(s.secEvents, esc(s.eventsMeta), '<div class="card list">' + events + "</div>") +
      section(s.secTasks, esc(s.tasksMeta), '<div class="card list">' + tasks + "</div>") +
      section(s.secDeadlines, esc(s.deadlinesMeta), '<div class="card list">' + deadlines + "</div>") +
      tiles + "</div>" + fab(s) + bottomBar(dir, s, "today");
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Chat                                                        */
  /* ------------------------------------------------------------------ */

  function spinner(dir) {
    if (dir === "B") {
      return '<svg class="spin spin-arc" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9" class="spin-track"/><path d="M12 3a9 9 0 0 1 9 9" class="spin-head"/></svg>';
    }
    if (dir === "C") {
      return '<span class="spin spin-blocks" aria-hidden="true"><i class="f"></i><i class="f"></i><i class="f"></i><i></i><i></i><i></i></span>';
    }
    return '<span class="spin spin-dots" aria-hidden="true"><i></i><i></i><i></i></span>';
  }

  function screenChat(dir, lang) {
    var s = STRINGS[lang];
    function userMsg(text) {
      return '<div class="msg msg-user"><div class="who">' + esc(s.you) + '</div><div class="bubble"><p>' + esc(text) + "</p></div></div>";
    }
    var answer = '<div class="msg msg-bot"><div class="who" lang="en">' + esc(s.assistant) + '</div><div class="bubble">' +
      "<p>" + esc(s.chatA1) + "</p><ul><li>" + esc(s.chatLi1) + "</li><li>" + esc(s.chatLi2) + "</li><li>" + esc(s.chatLi3) + "</li></ul>" +
      "<p>" + esc(s.chatA2) + "</p>" +
      '<div class="code"><div class="code-head">' + esc(s.codeLabel) + "</div>" +
      '<pre><code><span class="c-dim">$</span> ls notes/alpha\noutline.md   check-in.md</code></pre></div>' +
      "</div></div>";
    var working = '<div class="working" role="status">' + spinner(dir) +
      '<span class="w-text">' + esc(s.working) + ' <span class="num">0:12</span></span>' +
      '<span class="w-cancel" role="button">' + esc(s.cancel) + "</span></div>";
    var composer = '<div class="composer"><div class="field">' +
      (dir === "C" ? '<span class="prompt" aria-hidden="true">&gt;</span>' : "") +
      '<span class="placeholder">' + esc(s.composer) + "</span></div>" +
      '<span class="send" role="button" aria-label="' + esc(s.send) + '">' +
      (dir === "C" ? '<span class="send-lbl">' + esc(s.send) + "</span>" : icon(dir === "B" ? "plane" : "up")) + "</span></div>";

    return topBar(dir, s, s.chat, { newChat: true }) +
      '<div class="content content-chat"><div class="thread">' + userMsg(s.chatQ1) + answer + userMsg(s.chatQ2) + working + "</div></div>" +
      composer + fab(s) + bottomBar(dir, s, "chat");
  }

  /* ------------------------------------------------------------------ */
  /* Charts                                                              */
  /* ------------------------------------------------------------------ */

  var CHART = {
    A: { width: 322, barW: 16, radius: 3, rowH: 25, barH: 6, right: 66 },
    B: { width: 326, barW: 26, radius: 9, rowH: 24, barH: 10, right: 66 },
    C: { width: 358, barW: 30, radius: 0, rowH: 24, barH: 12, right: 80 }
  };

  function sleepStats() {
    var total = 0, lo = 0, hi = 0;
    DATA.sleep.forEach(function (n, i) {
      total += n.minutes;
      if (n.minutes < DATA.sleep[lo].minutes) { lo = i; }
      if (n.minutes > DATA.sleep[hi].minutes) { hi = i; }
    });
    return { avg: Math.round(total / DATA.sleep.length), lo: lo, hi: hi };
  }

  function nightDate(index) { return addDays(TODAY, index - (DATA.sleep.length - 1)); }

  function sleepSummary(lang) {
    var s = STRINGS[lang];
    var st = sleepStats();
    return s.sleepSummary(durText(st.avg, lang),
      fmtDate(nightDate(st.lo), { weekday: "long" }, lang), durText(DATA.sleep[st.lo].minutes, lang),
      fmtDate(nightDate(st.hi), { weekday: "long" }, lang), durText(DATA.sleep[st.hi].minutes, lang));
  }

  /** Seven-night bar chart with an average line; estimated nights are hatched. */
  function sleepChart(dir, lang, hatchId) {
    var s = STRINGS[lang];
    var cfg = CHART[dir];
    var W = cfg.width, H = 148;
    var left = 30, right = cfg.right, top = 18, plotH = 92;
    var base = top + plotH;
    var maxMin = 540;
    var st = sleepStats();
    function y(min) { return top + plotH * (1 - min / maxMin); }

    var svg = '<svg class="chart chart-sleep" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(sleepSummary(lang)) + '">';
    // Referenced through an inline style: a CSS fill on .bar would override a fill attribute.
    svg += '<defs><pattern id="' + hatchId + '" patternUnits="userSpaceOnUse" width="6" height="6" patternTransform="rotate(45)">' +
      '<rect width="6" height="6" class="hatch-bg"/><line x1="1.5" y1="0" x2="1.5" y2="6" class="hatch-line"/></pattern></defs>';

    [240, 480].forEach(function (min) {
      svg += '<line class="grid" x1="' + left + '" x2="' + (W - right) + '" y1="' + y(min) + '" y2="' + y(min) + '"/>' +
        '<text class="axis-lbl" x="' + (left - 6) + '" y="' + (y(min) + 3.5) + '" text-anchor="end">' + (min / 60) + " " + s.unitH + "</text>";
    });
    svg += '<line class="baseline" x1="' + left + '" x2="' + (W - right) + '" y1="' + base + '" y2="' + base + '"/>';

    var slot = (W - left - right) / DATA.sleep.length;
    DATA.sleep.forEach(function (n, i) {
      var date = nightDate(i);
      var cx = left + slot * (i + 0.5);
      var x0 = cx - cfg.barW / 2, x1 = cx + cfg.barW / 2;
      var yt = y(n.minutes), r = cfg.radius;
      var d = "M" + x0 + "," + base + "V" + (yt + r) + "Q" + x0 + "," + yt + " " + (x0 + r) + "," + yt +
        "H" + (x1 - r) + "Q" + x1 + "," + yt + " " + x1 + "," + (yt + r) + "V" + base + "Z";
      var label = fmtDate(date, { weekday: "long" }, lang) + ": " + durText(n.minutes, lang) + (n.est ? " (" + s.estimated + ")" : "");
      svg += '<path class="bar' + (n.est ? " bar-est" : "") + '" d="' + d + '"' +
        (n.est ? ' style="fill:url(#' + hatchId + ')"' : "") + "><title>" + esc(label) + "</title></path>";
      if (n.est) {
        svg += '<text class="est-lbl" x="' + cx + '" y="' + (yt - 5) + '" text-anchor="middle">' + esc(s.estShort) + "</text>";
      }
      svg += '<text class="x-lbl' + (i === DATA.sleep.length - 1 ? " x-last" : "") + '" x="' + cx + '" y="' + (base + 15) +
        '" text-anchor="middle">' + esc(fmtDate(date, { weekday: "short" }, lang)) + "</text>" +
        '<text class="val-lbl" x="' + cx + '" y="' + (base + 30) + '" text-anchor="middle">' +
        fmtNum(n.minutes / 60, lang, 1) + "</text>";
    });

    var ya = y(st.avg);
    svg += '<line class="avg" x1="' + left + '" x2="' + (W - right + 6) + '" y1="' + ya + '" y2="' + ya + '"/>' +
      '<text class="avg-lbl avg-lbl-k" x="' + (W - right + 10) + '" y="' + (ya - 3) + '">' + esc(s.avgShort) + "</text>" +
      '<text class="avg-lbl avg-lbl-v" x="' + (W - right + 10) + '" y="' + (ya + 10) + '">' + esc(durText(st.avg, lang)) + "</text>";
    return svg + "</svg>";
  }

  function sleepLegend(lang, hatchId) {
    var s = STRINGS[lang];
    return '<div class="legend"><span class="lg"><svg viewBox="0 0 16 12" width="16" height="12" aria-hidden="true">' +
      '<rect class="bar bar-est" x="0.75" y="0.75" width="14.5" height="10.5" style="fill:url(#' + hatchId + ')"/></svg>' + esc(s.estimated) + "</span>" +
      '<span class="lg"><svg viewBox="0 0 20 12" width="20" height="12" aria-hidden="true"><line class="avg" x1="0" x2="20" y1="6" y2="6"/></svg>' +
      esc(s.average) + "</span></div>";
  }

  /** Top apps as horizontal bars with direct labels. */
  function appsChart(dir, lang) {
    var s = STRINGS[lang];
    var cfg = CHART[dir];
    var W = cfg.width, rowH = cfg.rowH;
    var nameW = 78, valW = 52;
    var trackX = nameW, trackW = W - nameW - valW;
    var max = DATA.appMinutes[0];
    var list = DATA.appMinutes.map(function (m, i) { return s.apps[i] + " " + durText(m, lang); }).join(", ");
    var svg = '<svg class="chart chart-apps" viewBox="0 0 ' + W + " " + (rowH * DATA.appMinutes.length) +
      '" role="img" aria-label="' + esc(s.appsSummary(list)) + '">';
    DATA.appMinutes.forEach(function (m, i) {
      var cy = rowH * i + rowH / 2;
      var w = Math.round(trackW * m / max);
      svg += '<text class="app-name" x="0" y="' + (cy + 4) + '">' + esc(s.apps[i]) + "</text>";
      if (dir === "C") {
        // Console look: a row of discrete cells, filled up to the value.
        var cell = 9, gap = 3, n = Math.floor((trackW + gap) / (cell + gap));
        var filled = Math.max(1, Math.round(n * m / max));
        for (var k = 0; k < n; k += 1) {
          svg += '<rect class="' + (k < filled ? "hbar" : "hcell") + '" x="' + (trackX + k * (cell + gap)) + '" y="' +
            (cy - cfg.barH / 2) + '" width="' + cell + '" height="' + cfg.barH + '"/>';
        }
      } else {
        svg += '<rect class="htrack" x="' + trackX + '" y="' + (cy - cfg.barH / 2) + '" width="' + trackW + '" height="' + cfg.barH +
          '" rx="' + cfg.barH / 2 + '"/><rect class="hbar" x="' + trackX + '" y="' + (cy - cfg.barH / 2) + '" width="' + w +
          '" height="' + cfg.barH + '" rx="' + cfg.barH / 2 + '"><title>' + esc(s.apps[i] + ": " + durText(m, lang)) + "</title></rect>";
      }
      svg += '<text class="app-val" x="' + W + '" y="' + (cy + 4) + '" text-anchor="end">' + esc(durText(m, lang)) + "</text>";
    });
    return svg + "</svg>";
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Activity                                                    */
  /* ------------------------------------------------------------------ */

  function screenActivity(dir, lang) {
    var s = STRINGS[lang];
    var hatchId = nextId("hatch");
    var ln = DATA.lastNight;
    var nightRange = fmtDate(addDays(TODAY, -1), { weekday: "short" }, lang) + " → " +
      fmtDate(TODAY, { weekday: "short", day: "numeric", month: "short" }, lang);

    var tabs = '<div class="tabs" role="tablist"><span class="tab on" role="tab" aria-selected="true">' + esc(s.tabSleep) +
      '</span><span class="tab" role="tab" aria-selected="false">' + esc(s.tabScreen) + "</span></div>";

    var last = section(s.lastNight, esc(nightRange),
      '<div class="card stats"><div class="stat"><div class="stat-k">' + esc(s.bed) + '</div><div class="stat-v"><span class="num">' + ln.bed + "</span></div></div>" +
      '<div class="stat"><div class="stat-k">' + esc(s.wake) + '</div><div class="stat-v"><span class="num">' + ln.wake + "</span></div></div>" +
      '<div class="stat stat-main"><div class="stat-k">' + esc(s.duration) + '</div><div class="stat-v">' + durHtml(ln.minutes, lang) + "</div></div></div>",
      "sec-last");

    var range = '<span class="seg" role="group"><span class="seg-i on" aria-pressed="true">' + esc(s.days7) +
      '</span><span class="seg-i" aria-pressed="false">' + esc(s.days30) + "</span></span>";
    var chart = section(s.sleepChart, range,
      '<div class="card chart-card">' + sleepChart(dir, lang, hatchId) + sleepLegend(lang, hatchId) +
      '<p class="summary">' + esc(sleepSummary(lang)) + "</p></div>", "sec-chart");

    var screen = section(s.screenToday, "",
      '<div class="card screen-card"><div class="screen-head"><div class="screen-total">' + durHtml(DATA.screenMinutes, lang) + "</div>" +
      '<div class="screen-meta">' + esc(s.unlocks(DATA.unlocks)) + " · " + esc(s.topApps) + "</div></div>" + appsChart(dir, lang) + "</div>",
      "sec-screen");

    return topBar(dir, s, s.activity, { back: true }) +
      '<div class="content content-activity">' + tabs + last + chart + screen + "</div>" +
      fab(s) + bottomBar(dir, s, "more");
  }

  /* ------------------------------------------------------------------ */
  /* Page assembly                                                       */
  /* ------------------------------------------------------------------ */

  var SCREENS = { today: screenToday, chat: screenChat, activity: screenActivity };

  function phone(dir, screen, theme, lang, caption, sub) {
    return '<figure class="frame"><div class="device"><div class="phone screen-' + screen + '" data-dir="' + dir +
      '" data-theme="' + theme + '" lang="' + lang + '">' + statusBar() + SCREENS[screen](dir, lang) + "</div></div>" +
      '<figcaption><strong>' + esc(caption) + "</strong>" + (sub ? "<span>" + esc(sub) + "</span>" : "") + "</figcaption></figure>";
  }

  function segmented(key, label, options, disabled) {
    var html = '<div class="ctl' + (disabled ? " is-off" : "") + '"><span class="ctl-lbl">' + esc(label) + '</span><span class="ctl-seg" role="group" aria-label="' + esc(label) + '">';
    options.forEach(function (opt) {
      var on = state[key] === opt.value;
      html += '<button type="button" data-key="' + key + '" data-value="' + opt.value + '" aria-pressed="' + on + '"' +
        (disabled ? " disabled" : "") + ">" + esc(opt.label) + "</button>";
    });
    return html + "</span></div>";
  }

  function render() {
    var s = STRINGS[state.lang];
    var compare = state.view === "compare";
    uid = 0;

    document.documentElement.lang = state.lang;
    document.title = s.pageTitle;
    document.body.setAttribute("data-page-theme", state.theme);
    document.body.setAttribute("data-view", state.view);

    document.getElementById("chrome").innerHTML =
      '<div class="chrome-title"><h1>' + esc(s.pageTitle) + "</h1><p>" + esc(s.pageNote) + "</p></div>" +
      '<div class="ctls">' +
      segmented("view", s.ctlView, [{ value: "single", label: s.viewSingle }, { value: "compare", label: s.viewCompare }]) +
      segmented("dir", s.ctlDir, OPTIONS.dir.map(function (d) { return { value: d, label: d + " · " + s.dirName[d] }; }), compare) +
      segmented("screen", s.ctlScreen, OPTIONS.screen.map(function (k) { return { value: k, label: s[k] }; }), !compare) +
      segmented("theme", s.ctlTheme, [{ value: "light", label: s.light }, { value: "dark", label: s.dark }]) +
      segmented("lang", s.ctlLang, [{ value: "en", label: "EN" }, { value: "tr", label: "TR" }]) +
      "</div>";

    var stage = "";
    if (compare) {
      OPTIONS.dir.forEach(function (d) {
        stage += phone(d, state.screen, state.theme, state.lang, d + " · " + s.dirName[d], s.dirBlurb[d]);
      });
    } else {
      stage += '<p class="blurb"><strong>' + esc(state.dir + " · " + s.dirName[state.dir]) + "</strong> " + esc(s.dirBlurb[state.dir]) + "</p>";
      OPTIONS.screen.forEach(function (k) {
        stage += phone(state.dir, k, state.theme, state.lang, s[k], "");
      });
    }
    document.getElementById("stage").innerHTML = stage;
    document.getElementById("foot").textContent = s.pageNote;
    writeUrl();
  }

  document.addEventListener("click", function (event) {
    var btn = event.target.closest ? event.target.closest("button[data-key]") : null;
    if (!btn || btn.disabled) { return; }
    state[btn.getAttribute("data-key")] = btn.getAttribute("data-value");
    render();
  });

  readUrl();
  render();
}());
