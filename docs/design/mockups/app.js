/*
 * LifeOS visual-direction mockups.
 * One content model, one EN/TR string table, four direction skins.
 * D is the chosen skin and comes in three token variants (D1, D2, D3) that
 * share every layout rule; A, B and C are kept for reference.
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
      ctlView: "View", viewSingle: "One direction", viewCompare: "Compare D1 · D2 · D3", viewDirections: "Earlier directions",
      ctlDir: "Direction", ctlScreen: "Screen", ctlTheme: "Theme", ctlLang: "Language",
      light: "Light", dark: "Dark", both: "Both",
      notChosen: "not chosen",
      dirName: { A: "Calm paper", B: "Material You", C: "Dark console", D1: "Blue night", D2: "Warm glow", D3: "Balanced" },
      dirBlurb: {
        D1: "Cool and calm: a soft blue-white in light, a deep blue-black in dark. The accent is led by blue and runs through violet to pink; the ring is thin.",
        D2: "Warm and lively: a warm off-white in light, charcoal in dark. The accent is led by the gradient, from orange through pink to violet; the ring is the widest.",
        D3: "In between: neutral soft greys in light, a blue-tinted charcoal in dark. The accent runs from violet through pink to orange; the ring is medium.",
        A: "A quiet notebook: warm paper, serif titles, hairline cards, one deep-teal accent.",
        B: "Native and familiar: tonal surfaces and standard Material 3 shapes. Colours would follow the phone's wallpaper; one example palette is shown.",
        C: "A focused tool: near-black, high contrast, monospace headings and numbers, one amber accent. Dark is its primary look."
      },
      today: "Today", chat: "Chat", tasks: "Tasks", more: "More", activity: "Activity", sessions: "Sessions",
      capture: "Capture a note or task", back: "Back", nav: "Main navigation",
      connected: "Connected", connectedLong: "Connected to the computer",
      search: "Search", reminders: "Reminders",
      newReminders: function (n) { return n + " new reminders"; },
      unread: function (n) { return n + " unread"; },
      adviceLabel: "Today's focus",
      adviceText: "Finish the Project Alpha outline before anything else. The rest of the week builds on it.",
      why: "Why",
      adviceWhy: "Due today, and two other tasks wait for it.",
      secTop: "Top three", topNote: "chosen by the assistant",
      secToday: "Today", todayNote: "4 tasks · 1 done",
      task1: "Draft the Project Alpha outline", task1Why: "Due today; two tasks depend on it", task1Sub: "Today · Project Alpha",
      task2: "Submit Example assignment 1", task2Why: "One day late; about 30 minutes of work", task2Sub: "Yesterday · Example course",
      task3: "Reply about the seminar topic", task3Why: "Waiting for 6 days", task3Sub: "No date · Example seminar",
      task4: "Read chapter 3 of the example book", task4Sub: "Today · Example seminar",
      task5: "Buy printer paper", task5Sub: "Done",
      task6: "Review the check-in notes", task6Sub: "Thursday · Project Alpha",
      task7: "Renew the library book", task7Sub: "Friday",
      overdue: "Overdue",
      secImportant: "Most important", importantNote: "ordered by the assistant",
      showAll: function (n) { return "Show all (" + n + ")"; },
      tasksFoot: function (shown, n) { return shown + " of " + n + " open tasks shown"; },
      newChat: "New chat", you: "You", assistant: "LifeOS",
      chatQ1: "What is still open for Project Alpha this week?",
      chatA1: "Three things are still open:",
      chatLi1: "Draft the outline — due today",
      chatLi2: "Review the check-in notes",
      chatLi3: "Write the milestone summary by Tuesday",
      chatA2: "The notes are in the project folder:",
      codeLabel: "shell",
      chatQ2: "Add the summary as a task for Monday.",
      coach: "Coach", skip: "Skip", answer: "Answer",
      coachQ: "“Reply about the seminar topic” has been waiting for 6 days. What is hard about it?",
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
      unitH: "h", unitMin: "min",
      searchSessions: "Search sessions",
      pinned: "Pinned", byFolder: "By folder", yourGroup: "your group", automatic: "automatic",
      group1: "Thesis", group2: "Side project",
      inUse: "In use", reminderSet: "Reminder set",
      ses1: "Weekly plan", ses2: "Chapter 2 outline", ses3: "Literature notes",
      ses4: "Fix the login bug", ses5: "Summarise lecture 4", ses6: "Tidy the backup script",
      ago12m: "12 min ago", ago2h: "2 h ago", yesterday: "Yesterday", ago3d: "3 days ago", lastWeek: "Last week"
    },
    tr: {
      pageTitle: "LifeOS uygulaması — görsel yönler",
      pageNote: "Durağan taslak. Tüm içerik uydurma örnek veridir.",
      ctlView: "Görünüm", viewSingle: "Tek yön", viewCompare: "D1 · D2 · D3'ü karşılaştır", viewDirections: "Önceki yönler",
      ctlDir: "Yön", ctlScreen: "Ekran", ctlTheme: "Tema", ctlLang: "Dil",
      light: "Açık", dark: "Koyu", both: "İkisi de",
      notChosen: "seçilmedi",
      dirName: { A: "Sakin kâğıt", B: "Material You", C: "Koyu konsol", D1: "Mavi gece", D2: "Sıcak ışıltı", D3: "Dengeli" },
      dirBlurb: {
        D1: "Serin ve sakin: açık temada yumuşak mavimsi beyaz, koyu temada derin lacivert-siyah. Vurguyu mavi taşır; geçiş maviden mora, oradan pembeye uzanır. Halka incedir.",
        D2: "Sıcak ve canlı: açık temada sıcak kırık beyaz, koyu temada kömür grisi. Vurguyu renk geçişi taşır: turuncudan pembeye, oradan mora. Halka en kalın olanıdır.",
        D3: "İkisinin arası: açık temada nötr yumuşak griler, koyu temada mavimsi kömür grisi. Geçiş mordan pembeye, oradan turuncuya uzanır. Halka orta kalınlıktadır.",
        A: "Sessiz bir defter: sıcak kâğıt tonu, serif başlıklar, ince çizgili kartlar, tek bir koyu turkuaz vurgu.",
        B: "Tanıdık ve yerel: tonlu yüzeyler ve standart Material 3 biçimleri. Renkler telefonun duvar kâğıdından gelir; burada örnek bir palet gösteriliyor.",
        C: "Odaklı bir araç: siyaha yakın zemin, yüksek kontrast, eş aralıklı başlık ve rakamlar, tek bir kehribar vurgu. Asıl görünümü koyu temadır."
      },
      today: "Bugün", chat: "Sohbet", tasks: "Görevler", more: "Daha fazla", activity: "Aktivite", sessions: "Oturumlar",
      capture: "Not ya da görev ekle", back: "Geri", nav: "Ana gezinme",
      connected: "Bağlı", connectedLong: "Bilgisayara bağlı",
      search: "Ara", reminders: "Hatırlatıcılar",
      newReminders: function (n) { return n + " yeni hatırlatıcı"; },
      unread: function (n) { return n + " okunmamış"; },
      adviceLabel: "Bugünün odağı",
      adviceText: "Her şeyden önce Alfa Projesi taslağını bitir. Haftanın geri kalanı ona dayanıyor.",
      why: "Neden",
      adviceWhy: "Son günü bugün; iki görev de onu bekliyor.",
      secTop: "İlk üç", topNote: "asistanın seçimi",
      secToday: "Bugün", todayNote: "4 görev · 1 tamam",
      task1: "Alfa Projesi taslağını hazırla", task1Why: "Son gün bugün; iki görev buna bağlı", task1Sub: "Bugün · Alfa Projesi",
      task2: "Örnek ödev 1'i teslim et", task2Why: "Bir gün gecikti; yaklaşık 30 dakikalık iş", task2Sub: "Dün · Örnek ders",
      task3: "Seminer konusu için yanıt yaz", task3Why: "6 gündür bekliyor", task3Sub: "Tarihsiz · Örnek seminer",
      task4: "Örnek kitabın 3. bölümünü oku", task4Sub: "Bugün · Örnek seminer",
      task5: "Yazıcı kâğıdı al", task5Sub: "Tamamlandı",
      task6: "Görüşme notlarını gözden geçir", task6Sub: "Perşembe · Alfa Projesi",
      task7: "Kütüphane kitabının süresini uzat", task7Sub: "Cuma",
      overdue: "Gecikti",
      secImportant: "En önemliler", importantNote: "asistan sıraladı",
      showAll: function (n) { return "Tümünü göster (" + n + ")"; },
      tasksFoot: function (shown, n) { return n + " açık görevden " + shown + " tanesi gösteriliyor"; },
      newChat: "Yeni sohbet", you: "Sen", assistant: "LifeOS",
      chatQ1: "Alfa Projesi için bu hafta neler kaldı?",
      chatA1: "Hâlâ açık üç iş var:",
      chatLi1: "Taslağı hazırla — son gün bugün",
      chatLi2: "Görüşme notlarını gözden geçir",
      chatLi3: "Salıya kadar aşama özetini yaz",
      chatA2: "Notlar proje klasöründe:",
      codeLabel: "kabuk",
      chatQ2: "Özeti pazartesiye görev olarak ekle.",
      coach: "Koç", skip: "Geç", answer: "Yanıtla",
      coachQ: "“Seminer konusu için yanıt yaz” 6 gündür bekliyor. Seni zorlayan ne?",
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
      unitH: "sa", unitMin: "dk",
      searchSessions: "Oturumlarda ara",
      pinned: "Sabitlenenler", byFolder: "Klasöre göre", yourGroup: "senin grubun", automatic: "otomatik",
      group1: "Tez", group2: "Yan proje",
      inUse: "Kullanımda", reminderSet: "Hatırlatıcı var",
      ses1: "Haftalık plan", ses2: "2. bölüm taslağı", ses3: "Kaynak notları",
      ses4: "Giriş hatasını düzelt", ses5: "4. dersi özetle", ses6: "Yedekleme betiğini toparla",
      ago12m: "12 dk önce", ago2h: "2 sa önce", yesterday: "Dün", ago3d: "3 gün önce", lastWeek: "Geçen hafta"
    }
  };

  /* ------------------------------------------------------------------ */
  /* Placeholder data (identical for every direction)                    */
  /* ------------------------------------------------------------------ */

  var LOCALE = { en: "en-GB", tr: "tr-TR" };
  var TODAY = new Date(2026, 9, 14); // fixed, invented "today": a Wednesday

  var DATA = {
    // Today: the assistant's three picks, then everything that belongs to today.
    top: [
      { title: "task1", why: "task1Why" },
      { title: "task2", why: "task2Why" },
      { title: "task3", why: "task3Why" }
    ],
    today: [
      { title: "task1", sub: "task1Sub" },
      { title: "task2", sub: "task2Sub", overdue: true },
      { title: "task4", sub: "task4Sub" },
      { title: "task5", sub: "task5Sub", done: true }
    ],
    // Tasks: the assistant's short list out of all open tasks.
    important: [
      { title: "task1", sub: "task1Sub" },
      { title: "task2", sub: "task2Sub", overdue: true },
      { title: "task3", sub: "task3Sub" },
      { title: "task6", sub: "task6Sub" },
      { title: "task7", sub: "task7Sub" }
    ],
    openTasks: 23,
    // Counts shown as red badges (direction D): the bell on Today and the chat icon.
    newReminders: 3,
    unreadChat: 1,
    // Sessions: pinned, owner-made groups, then automatic groups by folder.
    sessions: {
      pinned: [{ title: "ses1", folder: "planning", when: "ago12m", inUse: true }],
      groups: [
        { name: "group1", items: [
          { title: "ses2", folder: "thesis", when: "ago2h", reminder: true },
          { title: "ses3", folder: "thesis", when: "yesterday", unread: 2 }
        ] },
        { name: "group2", items: [{ title: "ses4", folder: "project-alpha", when: "yesterday" }] }
      ],
      folders: [
        { folder: "course-notes", items: [{ title: "ses5", when: "ago3d" }] },
        { folder: "scripts", items: [{ title: "ses6", when: "lastWeek" }] }
      ]
    },
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
    view: ["single", "compare", "directions"],
    dir: ["D1", "D2", "D3", "A", "B", "C"],
    screen: ["today", "chat", "tasks", "sessions", "activity"],
    theme: ["light", "dark", "both"],
    lang: ["en", "tr"]
  };
  // D1, D2 and D3 are token variants of one skin ("D"): same markup, same
  // layout rules, different custom-property values.
  var VARIANTS = ["D1", "D2", "D3"];
  function skinOf(dir) { return VARIANTS.indexOf(dir) === -1 ? dir : "D"; }
  // Screens drawn per skin. Tasks and Sessions exist only in the chosen skin.
  var SKIN_SCREENS = {
    A: ["today", "chat", "activity"],
    B: ["today", "chat", "activity"],
    C: ["today", "chat", "activity"],
    D: ["today", "chat", "tasks", "sessions", "activity"]
  };
  var CHOSEN = "D1"; // the default variant
  var ALIASES = { dir: { d: "D1" } }; // links made before D was split still work
  var state = { view: "single", dir: CHOSEN, screen: "today", theme: "light", lang: "en" };

  function readUrl() {
    var params;
    try { params = new URLSearchParams(window.location.search); } catch (e) { return; }
    var given = {};
    params.forEach(function (value, key) { given[key.toLowerCase()] = value; });
    Object.keys(OPTIONS).forEach(function (key) {
      var raw = given[key];
      if (!raw) { return; }
      var wanted = raw.toLowerCase();
      if (ALIASES[key] && ALIASES[key][wanted]) { wanted = ALIASES[key][wanted].toLowerCase(); }
      var match = OPTIONS[key].filter(function (opt) {
        return opt.toLowerCase() === wanted;
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
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5 5"/>',
    bell: '<path d="M6.5 17v-6a5.5 5.5 0 0 1 11 0v6M4.5 17h15M10 20h4"/>',
    pin: '<path d="M9 3.5h6l-1 6 3 3.5H7l3-3.5zM12 13v7.5"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/>',
    folder: '<path d="M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"/>'
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
    if (opts.newChat && dir !== "D") {
      html += '<span class="tb-action" role="button">' + icon(dir === "C" ? "plus" : "compose") +
        '<span class="tb-action-lbl">' + esc(s.newChat) + "</span></span>";
    }
    html += '<span class="status" role="button" title="' + esc(s.connectedLong) + '" aria-label="' + esc(s.connectedLong) + '">' +
      '<span class="status-dot" aria-hidden="true"></span>' +
      '<span class="status-lbl">' + esc(s.connected) + "</span></span>";
    if (dir === "D") {
      // Icon-only actions, each named for screen readers. The bell can carry a count.
      (opts.icons || []).forEach(function (name) {
        var label = { bell: s.reminders, clock: s.sessions, compose: s.newChat, search: s.search }[name];
        var count = name === "bell" ? opts.bellCount : 0;
        if (count) { label += " (" + s.newReminders(count) + ")"; }
        html += '<span class="tb-icon" role="button" aria-label="' + esc(label) + '" title="' + esc(label) + '">' + icon(name) +
          (count ? badge(count, "badge-corner") : "") + "</span>";
      });
    }
    return html + "</header>";
  }

  /** A small red count. Its meaning is carried by the parent's aria-label. */
  function badge(count, extraClass) {
    return '<span class="badge num ' + (extraClass || "") + '" aria-hidden="true">' + count + "</span>";
  }

  function fab(s) {
    return '<span class="fab" role="button" aria-label="' + esc(s.capture) + '" title="' + esc(s.capture) + '">' + icon("plus") + "</span>";
  }

  /** Floating capture button. In D the button sits in the bottom bar instead. */
  function floatingFab(dir, s) { return dir === "D" ? "" : fab(s); }

  function bottomBar(dir, s, current) {
    var items = ["today", "chat", "tasks", "more"];
    var html = '<nav class="bottombar" aria-label="' + esc(s.nav) + '">';
    items.forEach(function (key, i) {
      var on = key === current;
      // In D the chat icon shows an unread count while another screen is open.
      var count = dir === "D" && key === "chat" && !on ? DATA.unreadChat : 0;
      var name = s[key] + (count ? " (" + s.unread(count) + ")" : "");
      if (dir === "D" && i === 2) { html += fab(s); }
      // The label is hidden in D (icon-only bar), so the name also goes on the item.
      html += '<span class="nav-item' + (on ? " on" : "") + '" role="link" aria-label="' + esc(name) + '" title="' + esc(name) + '"' +
        (on ? ' aria-current="page"' : "") + ">" +
        '<span class="nav-ind">' + icon(key, on ? "is-on" : "") + (count ? badge(count, "badge-corner") : "") + "</span>" +
        '<span class="nav-lbl" aria-hidden="true">' + esc(s[key]) + "</span></span>";
    });
    return html + '<span class="gesture" aria-hidden="true"></span></nav>';
  }

  function section(label, note, body, extraClass) {
    return '<section class="sec ' + (extraClass || "") + '"><div class="sec-head"><h3>' + esc(label) + "</h3>" +
      '<span class="sec-rule" aria-hidden="true"></span>' +
      (note ? '<span class="sec-meta">' + note + "</span>" : "") + "</div>" + body + "</section>";
  }

  /** A task as a checkbox row: title, then one line of context. */
  function taskRow(s, t) {
    var cls = "row task" + (t.done ? " done" : "") + (t.overdue ? " overdue" : "");
    return '<div class="' + cls + '"><span class="check" role="checkbox" aria-checked="' + (t.done ? "true" : "false") +
      '" aria-label="' + esc(s[t.title]) + '">' + (t.done ? icon("check") : "") + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s">' +
      (t.overdue ? '<span class="tag tag-alert"><span class="tag-mark" aria-hidden="true">!</span>' + esc(s.overdue) + "</span>" : "") +
      "<span>" + esc(s[t.sub]) + "</span></div></div></div>";
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Today                                                       */
  /* ------------------------------------------------------------------ */

  function screenToday(dir, lang) {
    var s = STRINGS[lang];

    // The featured "coach card". In D it carries the coach label and the accent ring.
    var advice = '<section class="card advice"><div class="advice-k">' +
      (dir === "D" ? '<span class="coach-tag">' + esc(s.coach) + "</span>" : "") +
      "<span>" + esc(s.adviceLabel) + "</span></div>" +
      '<p class="advice-t">' + esc(s.adviceText) + "</p>" +
      '<p class="advice-why"><b>' + esc(s.why) + "</b> " + esc(s.adviceWhy) + "</p></section>";

    var top = DATA.top.map(function (t, i) {
      return '<div class="row pick"><span class="rank num" aria-hidden="true">' + (i + 1) + "</span>" +
        '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s"><span>' + esc(s[t.why]) + "</span></div></div></div>";
    }).join("");

    var tasks = DATA.today.map(function (t) { return taskRow(s, t); }).join("");

    return topBar(dir, s, s.today, { icons: ["bell"], bellCount: DATA.newReminders }) +
      '<div class="content content-today">' + advice +
      section(s.secTop, esc(s.topNote), '<div class="card list">' + top + "</div>", "sec-top") +
      section(s.secToday, esc(s.todayNote), '<div class="card list">' + tasks + "</div>", "sec-today") +
      "</div>" + floatingFab(dir, s) + bottomBar(dir, s, "today");
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
    // A question the assistant asks on its own initiative: labelled, and skippable.
    var coach = '<div class="msg msg-bot msg-coach"><div class="coach-head"><span class="coach-tag">' + esc(s.coach) + "</span>" +
      '<span class="coach-time num">08:30</span></div>' +
      '<div class="bubble"><p>' + esc(s.coachQ) + "</p></div>" +
      '<div class="coach-acts"><span class="coach-answer" role="button">' + esc(s.answer) + "</span>" +
      '<span class="coach-skip" role="button">' + esc(s.skip) + "</span></div></div>";
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

    return topBar(dir, s, s.chat, { newChat: true, icons: ["clock", "compose"] }) +
      '<div class="content content-chat"><div class="thread">' + coach + userMsg(s.chatQ1) + answer + userMsg(s.chatQ2) + working + "</div></div>" +
      composer + floatingFab(dir, s) + bottomBar(dir, s, "chat");
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Tasks                                                       */
  /* ------------------------------------------------------------------ */

  function screenTasks(dir, lang) {
    var s = STRINGS[lang];
    var rows = DATA.important.map(function (t) { return taskRow(s, t); }).join("");
    return topBar(dir, s, s.tasks, { icons: ["search"] }) +
      '<div class="content content-tasks">' +
      section(s.secImportant, esc(s.importantNote), '<div class="card list">' + rows + "</div>", "sec-important") +
      '<span class="btn btn-wide" role="button">' + esc(s.showAll(DATA.openTasks)) + "</span>" +
      '<p class="foot-note">' + esc(s.tasksFoot(DATA.important.length, DATA.openTasks)) + "</p>" +
      "</div>" + floatingFab(dir, s) + bottomBar(dir, s, "tasks");
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Sessions                                                    */
  /* ------------------------------------------------------------------ */

  function sessionRow(s, ses, folder, pinned) {
    var name = ses.folder || folder;
    return '<div class="row session"><span class="thumb' + (pinned ? " is-featured" : "") + '" aria-hidden="true">' + esc(name.charAt(0).toUpperCase()) + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s[ses.title]) + '</div><div class="row-s">' +
      (ses.folder ? '<span class="path">' + esc(ses.folder) + '</span><span aria-hidden="true">·</span>' : "") +
      "<span>" + esc(s[ses.when]) + "</span></div></div>" +
      (ses.inUse ? '<span class="tag tag-live"><span class="live-dot" aria-hidden="true"></span>' + esc(s.inUse) + "</span>" : "") +
      (ses.unread ? '<span class="badge num" role="img" aria-label="' + esc(s.unread(ses.unread)) + '" title="' + esc(s.unread(ses.unread)) + '">' + ses.unread + "</span>" : "") +
      (ses.reminder ? '<span class="row-icon" role="img" aria-label="' + esc(s.reminderSet) + '" title="' + esc(s.reminderSet) + '">' + icon("bell") + "</span>" : "") +
      (pinned ? '<span class="row-icon row-pin" aria-hidden="true">' + icon("pin") + "</span>" : "") + "</div>";
  }

  function screenSessions(dir, lang) {
    var s = STRINGS[lang];
    var data = DATA.sessions;
    function rows(items, folder, pinned) {
      return '<div class="card list">' + items.map(function (ses) { return sessionRow(s, ses, folder, pinned); }).join("") + "</div>";
    }

    var search = '<div class="search" role="search">' + icon("search") + '<span class="placeholder">' + esc(s.searchSessions) + "</span></div>";
    var groups = data.groups.map(function (g) {
      return section(s[g.name], esc(s.yourGroup), rows(g.items), "sec-group");
    }).join("");
    var folders = data.folders.map(function (f) {
      return '<div class="folder-head">' + icon("folder") + '<span class="path">' + esc(f.folder) + "</span></div>" + rows(f.items, f.folder);
    }).join("");

    return topBar(dir, s, s.sessions, { back: true, icons: ["compose"] }) +
      '<div class="content content-sessions">' + search +
      section(s.pinned, "", rows(data.pinned, "", true), "sec-pinned") + groups +
      section(s.byFolder, esc(s.automatic), folders, "sec-folders") +
      "</div>" + floatingFab(dir, s) + bottomBar(dir, s, "more");
  }

  /* ------------------------------------------------------------------ */
  /* Charts                                                              */
  /* ------------------------------------------------------------------ */

  var CHART = {
    A: { width: 322, barW: 16, radius: 3, rowH: 25, barH: 6, right: 66 },
    B: { width: 326, barW: 26, radius: 9, rowH: 24, barH: 10, right: 66 },
    C: { width: 358, barW: 30, radius: 0, rowH: 24, barH: 12, right: 80 },
    D: { width: 358, barW: 20, radius: 5, rowH: 26, barH: 6, right: 70 }
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
      floatingFab(dir, s) + bottomBar(dir, s, "more");
  }

  /* ------------------------------------------------------------------ */
  /* Page assembly                                                       */
  /* ------------------------------------------------------------------ */

  var SCREENS = { today: screenToday, chat: screenChat, tasks: screenTasks, sessions: screenSessions, activity: screenActivity };

  function phone(dir, screen, theme, lang, caption, sub) {
    var skin = skinOf(dir);
    return '<figure class="frame"><div class="device"><div class="phone screen-' + screen + '" data-dir="' + skin + '"' +
      (skin === dir ? "" : ' data-variant="' + dir + '"') +
      ' data-theme="' + theme + '" lang="' + lang + '">' + statusBar() + SCREENS[screen](skin, lang) + "</div></div>" +
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

  function dirLabel(s, d) {
    return d + " · " + s.dirName[d] + (skinOf(d) === "D" ? "" : " (" + s.notChosen + ")");
  }

  function render() {
    var s = STRINGS[state.lang];
    var single = state.view === "single";
    // "both" draws a light band and a dark band; the page chrome itself stays light.
    var themes = state.theme === "both" ? ["light", "dark"] : [state.theme];
    uid = 0;

    document.documentElement.lang = state.lang;
    document.title = s.pageTitle;
    document.body.setAttribute("data-page-theme", themes[0]);
    document.body.setAttribute("data-view", state.view);

    document.getElementById("chrome").innerHTML =
      '<div class="chrome-title"><h1>' + esc(s.pageTitle) + "</h1><p>" + esc(s.pageNote) + "</p></div>" +
      '<div class="ctls">' +
      segmented("view", s.ctlView, [{ value: "single", label: s.viewSingle }, { value: "compare", label: s.viewCompare },
        { value: "directions", label: s.viewDirections }]) +
      segmented("dir", s.ctlDir, OPTIONS.dir.map(function (d) { return { value: d, label: dirLabel(s, d) }; }), !single) +
      segmented("screen", s.ctlScreen, OPTIONS.screen.map(function (k) { return { value: k, label: s[k] }; }), single) +
      segmented("theme", s.ctlTheme, [{ value: "light", label: s.light }, { value: "dark", label: s.dark }, { value: "both", label: s.both }]) +
      segmented("lang", s.ctlLang, [{ value: "en", label: "EN" }, { value: "tr", label: "TR" }]) +
      "</div>";

    // compare: the three variants of the chosen skin. directions: the default
    // variant next to the earlier directions that have this screen.
    var dirs = state.view === "compare" ? VARIANTS : [CHOSEN, "A", "B", "C"].filter(function (d) {
      return SKIN_SCREENS[skinOf(d)].indexOf(state.screen) !== -1;
    });

    var stage = "";
    if (single) {
      stage += '<p class="blurb"><strong>' + esc(dirLabel(s, state.dir)) + "</strong> " + esc(s.dirBlurb[state.dir]) + "</p>";
    }
    themes.forEach(function (theme, i) {
      stage += '<section class="band" data-page-theme="' + theme + '">';
      if (themes.length > 1) { stage += '<h2 class="band-title">' + esc(s[theme]) + "</h2>"; }
      if (single) {
        SKIN_SCREENS[skinOf(state.dir)].forEach(function (k) {
          stage += phone(state.dir, k, theme, state.lang, s[k], "");
        });
      } else {
        dirs.forEach(function (d) {
          // The description is printed once, under the first band.
          stage += phone(d, state.screen, theme, state.lang, dirLabel(s, d), i === 0 ? s.dirBlurb[d] : "");
        });
      }
      stage += "</section>";
    });
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
