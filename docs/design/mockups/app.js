/*
 * LifeOS app mockups: three warmer proposals built on the approved D3.
 * One content model, one EN/TR string table, one set of screens.
 * "D3" draws the screens as approved; P1, P2 and P3 keep the D3 structure,
 * tokens, coach card and bottom bar, and change how the other cards look.
 * Everything shown is invented placeholder content. No network, no build step.
 */
(function () {
  "use strict";

  /* ------------------------------------------------------------------ */
  /* Strings                                                             */
  /* ------------------------------------------------------------------ */

  var STRINGS = {
    en: {
      pageTitle: "LifeOS app — warmer D3 proposals",
      pageNote: "Static mockup. All content is invented placeholder data.",
      ctlView: "View", viewCompare: "P1 · P2 · P3 side by side", viewSingle: "One proposal, all screens",
      ctlProp: "Proposal", ctlScreen: "Screen", ctlTheme: "Theme", ctlLang: "Language",
      light: "Light", dark: "Dark", both: "Both",
      propName: { D3: "D3 as approved", P1: "Soft tints", P2: "Course colours", P3: "Playful shapes" },
      propBlurb: {
        D3: "The approved D3, unchanged: neutral soft greys, blue for actions, red for counts, the gradient ring on the coach card. Shown for reference.",
        P1: "Colour per section. Every card is tinted in its section's soft pastel (picks violet, Today peach, Tasks mint, deadlines rose, courses sky) and its heading starts with a small icon tile. No patterns: the calmest of the three.",
        P2: "Colour per course. Each course (and project) keeps its own colour and line icon everywhere it appears: course tiles, task labels, deadlines, the course page and the assignment. Section cards stay light grey and carry one large, faint icon as a watermark.",
        P3: "Playful layouts. Cards vary in shape (one tighter corner, ticket edges, a wide tile among square ones) and carry small illustrations made of circles, stripes, dots and waves in the card's colour. Picks and courses become tiles."
      },
      asD3: "as in D3",
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
      secTop: "Assistant's picks",
      secToday: "Today",
      todayNote: function (n, done) { return n + " tasks · " + done + " done"; },
      openConversation: "Open conversation",
      moreCount: function (n) { return n + " more"; },
      task1: "Draft the Project Alpha outline", task1Why: "Due today; two tasks depend on it",
      task2: "Submit Exercise sheet 1", task2Why: "One day late; about 30 minutes of work",
      task3: "Reply about the essay topic", task3Why: "Waiting for 6 days",
      task4: "Read chapter 3 of the lecture notes",
      task5: "Buy printer paper",
      task6: "Review the check-in notes",
      task7: "Prepare the lab kit",
      task8: "Send the meeting notes",
      when: { today: "Today", yesterday: "Yesterday", none: "No date", thu: "Thursday", fri: "Friday", done: "Done" },
      ctx: {
        alpha: "Project Alpha", sig: "Signals and Systems", emb: "Embedded Systems Lab",
        lin: "Linear Algebra", wri: "Technical Writing", pro: "Probability and Statistics"
      },
      overdue: "Overdue",
      secImportant: "Most important", importantNote: "ordered by the assistant",
      upNext: "Up next",
      showAll: function (n) { return "Show all (" + n + ")"; },
      tasksFoot: function (shown, n) { return shown + " of " + n + " open tasks shown"; },
      newChat: "New chat", you: "You", assistant: "LifeOS",
      chatQ1: "What is still open for Project Alpha this week?",
      chatA1: "Three things are still open:",
      chatLi1: "Draft the outline — due today",
      chatLi2: "Review the check-in notes",
      chatLi3: "Write the milestone summary by Tuesday",
      chatA2: "The notes are in the project folder:",
      chatQ2: "Add the summary as a task for Monday and clear out the old drafts.",
      coach: "Coach",
      coachQ: "“Reply about the essay topic” has been waiting for 6 days. What is hard about it?",
      cap: {
        chatlist: "Chat · list", newchat: "New chat · folder", coach: "Coach conversation", chat: "Chat · conversation",
        course: "Course · Materials", "course-announcements": "Course · Announcements",
        "course-assignments": "Course · Assignments", "course-grades": "Course · Grades",
        assignment: "Assignment"
      },
      usageToday: function (n) { return "Today: " + n + " runs"; },
      recent: "Recent", allSessions: "All sessions",
      whereRun: "Where should this chat run?",
      memoryDefault: "Memory (default)", memorySub: "Notes, tasks and daily logs",
      allowedFolder: "Allowed folder", start: "Start",
      summarised: "Earlier messages were summarised",
      coachM1: "Good morning. Today's focus is the Project Alpha outline; two other tasks wait for it.",
      coachU1: "I'll start on it right after breakfast.",
      question: "Question",
      optA: "I haven't picked a topic yet", optB: "I don't know what to write", optC: "It isn't important any more",
      ownAnswer: "Or write your own answer",
      approvalLabel: "Approval needed",
      approvalText: ["The assistant wants to delete 3 files in ", ""],
      allow: "Allow", deny: "Don't allow",
      keptNote: "Changes already made are kept.",
      // Courses
      courses: "Courses", lastSync: "Last sync 2 h ago", refresh: "Refresh",
      secDeadlines: "Upcoming deadlines", deadlinesNote: "next 2 weeks",
      dueIn: function (n) { return "in " + n + " days"; },
      days: "days",
      allGrades: "All grades",
      secCourses: "All courses",
      courseCount: function (n) { return n + " courses"; },
      next: "Next:", noDeadline: "No upcoming deadline",
      newCount: function (n) { return n + " new"; },
      item: {
        as1: "Assignment 1", as2: "Assignment 2", quiz1: "Quiz 1", report: "Project report",
        lab1: "Lab report 1", summary: "Reading summary", midterm: "Midterm exam", talk: "Short presentation"
      },
      tabMaterials: "Materials", tabAnnouncements: "Announcements", tabAssignments: "Assignments", tabGrades: "Grades",
      week: function (n, title) { return "Week " + n + " · " + title; },
      weekTitle: { intro: "Introduction", core: "Core concepts" },
      file: {
        slides1: "Lecture 1 slides", outline: "Course outline", slides2: "Lecture 2 slides", sheet1: "Exercise sheet 1", data: "Example data set",
        sheet2: "Assignment 2 sheet", signals: "Sample signals"
      },
      newTag: "New", download: "Download", saved: "Saved to Downloads", downloaded: "Downloaded",
      links: "Links", open: "Open",
      link: { recording: "Lecture 2 recording", reading: "Reading list" },
      video: "Video", externalLink: "External link",
      ann: {
        a1: "Lecture 2 slides are up", a1Body: function () { return "The slides and the first exercise sheet are in Week 2."; },
        a2: "Assignment 2 published", a2Body: function (d) { return "Due on " + d + ". Hand in one PDF file."; },
        a3: "Room change this week", a3Body: function () { return "Thursday's lecture moves to room B-12."; },
        a4: "Welcome to the course", a4Body: function () { return "The outline and the grading scheme are under Materials."; }
      },
      due: function (d) { return "Due " + d; },
      st: { open: "Not submitted", later: "Not open yet", submitted: "Submitted", graded: "Graded" },
      range: function (lo, hi) { return "Range " + lo + "–" + hi; },
      notGraded: "Not graded yet", noGrades: "No grades yet",
      total: "Total", courseTotal: "Course total so far",
      // Assignment detail
      dueLabel: "Due", opened: function (d) { return "Opened " + d; },
      instructions: "Instructions",
      asgText: "Solve problems 1–4 on the attached sheet. Show your working and hand in one PDF file.",
      files: "Files", submission: "Submission",
      status: "Status", format: "Format", formatText: "One PDF file, up to 20 MB",
      grade: "Grade", inTasks: "In your tasks", inTasksSub: function (d) { return "Due " + d + " · completes when submitted"; },
      openSubmit: "Open the submission page",
      composer: "Message LifeOS", send: "Send",
      tabSleep: "Sleep", tabScreen: "Screen time",
      lastNight: "Last night",
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
      pageTitle: "LifeOS uygulaması — daha sıcak D3 önerileri",
      pageNote: "Durağan taslak. Tüm içerik uydurma örnek veridir.",
      ctlView: "Görünüm", viewCompare: "P1 · P2 · P3 yan yana", viewSingle: "Tek öneri, tüm ekranlar",
      ctlProp: "Öneri", ctlScreen: "Ekran", ctlTheme: "Tema", ctlLang: "Dil",
      light: "Açık", dark: "Koyu", both: "İkisi de",
      propName: { D3: "Onaylanan D3", P1: "Yumuşak tonlar", P2: "Ders renkleri", P3: "Oyuncu biçimler" },
      propBlurb: {
        D3: "Onaylanan D3, değişmeden: nötr yumuşak griler, eylemler için mavi, sayılar için kırmızı, koç kartında renk geçişli halka. Karşılaştırma için gösteriliyor.",
        P1: "Bölüm başına renk. Her kart kendi bölümünün yumuşak pastel tonunu alır (seçtikleri mor, Bugün şeftali, Görevler nane, teslimler gül, dersler gök mavisi) ve başlığı küçük bir ikon karesiyle başlar. Desen yok: üçünün en sakini.",
        P2: "Ders başına renk. Her ders (ve proje) göründüğü her yerde kendi rengini ve çizgi ikonunu korur: ders kartları, görev etiketleri, teslimler, ders sayfası ve ödev. Bölüm kartları açık gri kalır ve filigran olarak tek bir büyük, soluk ikon taşır.",
        P3: "Oyuncu yerleşim. Kartların biçimi değişir (tek köşesi daha keskin, bilet kenarlı, kare kartlar arasında geniş bir kart) ve kartın renginde dairelerden, şeritlerden, noktalardan ve dalgalardan oluşan küçük çizimler taşır. Seçtikleri ve dersler karolara dönüşür."
      },
      asD3: "D3'teki gibi",
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
      secTop: "Asistanın seçtikleri",
      secToday: "Bugün",
      todayNote: function (n, done) { return n + " görev · " + done + " tamam"; },
      openConversation: "Sohbeti aç",
      moreCount: function (n) { return n + " tane daha"; },
      task1: "Alfa Projesi taslağını hazırla", task1Why: "Son gün bugün; iki görev buna bağlı",
      task2: "Alıştırma kâğıdı 1'i teslim et", task2Why: "Bir gün gecikti; yaklaşık 30 dakikalık iş",
      task3: "Deneme konusu için yanıt yaz", task3Why: "6 gündür bekliyor",
      task4: "Ders notlarının 3. bölümünü oku",
      task5: "Yazıcı kâğıdı al",
      task6: "Görüşme notlarını gözden geçir",
      task7: "Laboratuvar setini hazırla",
      task8: "Toplantı notlarını gönder",
      when: { today: "Bugün", yesterday: "Dün", none: "Tarihsiz", thu: "Perşembe", fri: "Cuma", done: "Tamamlandı" },
      ctx: {
        alpha: "Alfa Projesi", sig: "Sinyaller ve Sistemler", emb: "Gömülü Sistemler Lab.",
        lin: "Lineer Cebir", wri: "Teknik Yazım", pro: "Olasılık ve İstatistik"
      },
      overdue: "Gecikti",
      secImportant: "En önemliler", importantNote: "asistan sıraladı",
      upNext: "Sıradaki",
      showAll: function (n) { return "Tümünü göster (" + n + ")"; },
      tasksFoot: function (shown, n) { return n + " açık görevden " + shown + " tanesi gösteriliyor"; },
      newChat: "Yeni sohbet", you: "Sen", assistant: "LifeOS",
      chatQ1: "Alfa Projesi için bu hafta neler kaldı?",
      chatA1: "Hâlâ açık üç iş var:",
      chatLi1: "Taslağı hazırla — son gün bugün",
      chatLi2: "Görüşme notlarını gözden geçir",
      chatLi3: "Salıya kadar aşama özetini yaz",
      chatA2: "Notlar proje klasöründe:",
      chatQ2: "Özeti pazartesiye görev olarak ekle, eski taslakları da temizle.",
      coach: "Koç",
      coachQ: "“Deneme konusu için yanıt yaz” 6 gündür bekliyor. Seni zorlayan ne?",
      cap: {
        chatlist: "Sohbet · liste", newchat: "Yeni sohbet · klasör", coach: "Koç sohbeti", chat: "Sohbet · konuşma",
        course: "Ders · Materyaller", "course-announcements": "Ders · Duyurular",
        "course-assignments": "Ders · Ödevler", "course-grades": "Ders · Notlar",
        assignment: "Ödev"
      },
      usageToday: function (n) { return "Bugün: " + n + " çalıştırma"; },
      recent: "Son sohbetler", allSessions: "Tüm oturumlar",
      whereRun: "Bu sohbet nerede çalışsın?",
      memoryDefault: "Hafıza (varsayılan)", memorySub: "Notlar, görevler ve günlükler",
      allowedFolder: "İzin verilen klasör", start: "Başlat",
      summarised: "Önceki mesajlar özetlendi",
      coachM1: "Günaydın. Bugünün odağı Alfa Projesi taslağı; iki görev de onu bekliyor.",
      coachU1: "Kahvaltıdan hemen sonra başlayacağım.",
      question: "Soru",
      optA: "Henüz konu seçmedim", optB: "Ne yazacağımı bilmiyorum", optC: "Artık önemli değil",
      ownAnswer: "Ya da kendi yanıtını yaz",
      approvalLabel: "Onay gerekiyor",
      approvalText: ["Asistan ", " içinde 3 dosyayı silmek istiyor"],
      allow: "İzin ver", deny: "İzin verme",
      keptNote: "Yapılmış değişiklikler kalır.",
      courses: "Dersler", lastSync: "Son eşitleme 2 sa önce", refresh: "Yenile",
      secDeadlines: "Yaklaşan teslimler", deadlinesNote: "önümüzdeki 2 hafta",
      dueIn: function (n) { return n + " gün kaldı"; },
      days: "gün",
      allGrades: "Tüm notlar",
      secCourses: "Tüm dersler",
      courseCount: function (n) { return n + " ders"; },
      next: "Sıradaki:", noDeadline: "Yaklaşan teslim yok",
      newCount: function (n) { return n + " yeni"; },
      item: {
        as1: "Ödev 1", as2: "Ödev 2", quiz1: "Kısa sınav 1", report: "Proje raporu",
        lab1: "Deney raporu 1", summary: "Okuma özeti", midterm: "Ara sınav", talk: "Kısa sunum"
      },
      tabMaterials: "Materyaller", tabAnnouncements: "Duyurular", tabAssignments: "Ödevler", tabGrades: "Notlar",
      week: function (n, title) { return n + ". hafta · " + title; },
      weekTitle: { intro: "Giriş", core: "Temel kavramlar" },
      file: {
        slides1: "1. ders slaytları", outline: "Ders izlencesi", slides2: "2. ders slaytları", sheet1: "Alıştırma kâğıdı 1", data: "Örnek veri seti",
        sheet2: "Ödev 2 kâğıdı", signals: "Örnek sinyaller"
      },
      newTag: "Yeni", download: "İndir", saved: "İndirilenler'e kaydedildi", downloaded: "İndirildi",
      links: "Bağlantılar", open: "Aç",
      link: { recording: "2. ders kaydı", reading: "Okuma listesi" },
      video: "Video", externalLink: "Dış bağlantı",
      ann: {
        a1: "2. ders slaytları yüklendi", a1Body: function () { return "Slaytlar ve ilk alıştırma kâğıdı 2. hafta bölümünde."; },
        a2: "Ödev 2 yayımlandı", a2Body: function (d) { return "Son teslim tarihi " + d + ". Tek bir PDF dosyası yükleyin."; },
        a3: "Bu hafta derslik değişti", a3Body: function () { return "Perşembe günkü ders B-12 numaralı derslikte yapılacak."; },
        a4: "Derse hoş geldiniz", a4Body: function () { return "Ders izlencesi ve değerlendirme ölçütleri Materyaller sekmesinde."; }
      },
      due: function (d) { return "Teslim: " + d; },
      st: { open: "Teslim edilmedi", later: "Henüz açılmadı", submitted: "Teslim edildi", graded: "Notlandı" },
      range: function (lo, hi) { return "Aralık " + lo + "–" + hi; },
      notGraded: "Henüz notlanmadı", noGrades: "Henüz not yok",
      total: "Toplam", courseTotal: "Şimdiye kadarki ders toplamı",
      dueLabel: "Son teslim", opened: function (d) { return "Açılış " + d; },
      instructions: "Açıklama",
      asgText: "Ekteki kâğıtta 1–4. soruları çöz. Çözüm adımlarını göster ve tek bir PDF dosyası yükle.",
      files: "Dosyalar", submission: "Teslim",
      status: "Durum", format: "Biçim", formatText: "Tek PDF dosyası, en çok 20 MB",
      grade: "Not", inTasks: "Görevlerinde", inTasksSub: function (d) { return "Son gün " + d + " · teslim edilince kapanır"; },
      openSubmit: "Teslim sayfasını aç",
      composer: "LifeOS'a yaz", send: "Gönder",
      tabSleep: "Uyku", tabScreen: "Ekran süresi",
      lastNight: "Dün gece",
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
  /* Placeholder data (identical for every proposal)                     */
  /* ------------------------------------------------------------------ */

  var LOCALE = { en: "en-GB", tr: "tr-TR" };
  var TODAY = new Date(2026, 9, 14); // fixed, invented "today": a Wednesday

  // Contexts: the courses and the one project. Each takes the next colour of a
  // small fixed palette (by position, so new courses simply continue it) and
  // has its own line icon. Only P2 and P3 show the colour.
  var PALETTE = ["sky", "teal", "violet", "peach", "sand", "mint", "rose"];
  var CTX = {
    sig: { glyph: "wave", deco: "waves" },
    emb: { glyph: "chip", deco: "grid" },
    lin: { glyph: "matrix", deco: "dots" },
    wri: { glyph: "pen", deco: "arcs" },
    pro: { glyph: "dice", deco: "triangles" },
    alpha: { glyph: "folder", deco: "circles" }
  };
  ["sig", "emb", "lin", "wri", "pro", "alpha"].forEach(function (key, i) { CTX[key].tone = PALETTE[i]; });

  var DATA = {
    // Today: the assistant's three picks, then everything that belongs to today.
    top: [
      { title: "task1", why: "task1Why", ctx: "alpha" },
      { title: "task2", why: "task2Why", ctx: "sig" },
      { title: "task3", why: "task3Why", ctx: "wri" }
    ],
    today: [
      { title: "task1", when: "today", ctx: "alpha" },
      { title: "task2", when: "yesterday", ctx: "sig", overdue: true },
      { title: "task4", when: "today", ctx: "lin" },
      { title: "task8", when: "today", ctx: "alpha" },
      { title: "task5", when: "done", done: true }
    ],
    todayVisible: 3, // rows the Today card shows before it scrolls
    // Tasks: the assistant's short list out of all open tasks.
    important: [
      { title: "task1", when: "today", ctx: "alpha" },
      { title: "task2", when: "yesterday", ctx: "sig", overdue: true },
      { title: "task3", when: "none", ctx: "wri" },
      { title: "task6", when: "thu", ctx: "alpha" },
      { title: "task7", when: "fri", ctx: "emb" }
    ],
    openTasks: 23,
    newReminders: 3,
    unreadChat: 1,
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
    coachRuns: 2,
    recent: [
      { title: "ses2", folder: "thesis", when: "ago2h", unread: 1 },
      { title: "ses4", folder: "project-alpha", when: "yesterday" },
      { title: "ses5", folder: "course-notes", when: "ago3d" }
    ],
    allowedFolders: ["project-alpha", "course-notes"],
    approval: { path: "notes/drafts", files: ["plan-old.md", "outline-v1.md", "scratch.md"] },
    // Courses: day offsets are relative to the invented "today".
    courses: [
      { key: "sig", next: "as2", inDays: 2, fresh: 3, total: 86 },
      { key: "emb", next: "lab1", inDays: 5, fresh: 1, total: 90 },
      { key: "lin", next: "quiz1", inDays: 9, total: 74 },
      { key: "wri", total: 92 },
      { key: "pro", next: "midterm", inDays: 16 }
    ],
    materials: [
      { week: 1, title: "intro", files: [
        { name: "slides1", type: "PDF", mb: 2.4, day: -9, saved: true },
        { name: "outline", type: "PDF", mb: 0.2, day: -9 }
      ] },
      { week: 2, title: "core", files: [
        { name: "slides2", type: "PDF", mb: 3.1, day: -2, fresh: true },
        { name: "sheet1", type: "PDF", mb: 0.1, day: -2 },
        { name: "data", type: "ZIP", mb: 12.6, day: -2 }
      ] }
    ],
    links: [
      { name: "recording", kind: "video", minutes: 48 },
      { name: "reading", kind: "external" }
    ],
    announcements: [
      { key: "a1", day: -2, fresh: true },
      { key: "a2", day: -5, dueDay: 2 },
      { key: "a3", day: -7 },
      { key: "a4", day: -13 }
    ],
    assignments: [
      { item: "as2", day: 2, status: "open" },
      { item: "quiz1", day: 16, status: "later" },
      { item: "as1", day: -12, status: "graded", grade: 85, hi: 100 },
      { item: "summary", day: -19, status: "submitted" }
    ],
    // The assignment detail: Assignment 2 of the first course.
    assignment: {
      item: "as2", ctx: "sig", due: 2, time: "23:59", opened: -5, hi: 100,
      files: [{ name: "sheet2", type: "PDF", mb: 0.3, day: -5 }, { name: "signals", type: "ZIP", mb: 4.2, day: -5, saved: true }]
    },
    courseGrades: [
      { item: "as1", grade: 85, hi: 100 },
      { item: "talk", grade: 18, hi: 20 },
      { item: "summary", hi: 20 },
      { item: "as2", hi: 100 },
      { item: "midterm", hi: 100 }
    ],
    allGrades: [
      { key: "sig", total: 86, rows: [{ item: "as1", grade: 85, hi: 100 }, { item: "talk", grade: 18, hi: 20 }] },
      { key: "emb", total: 90, rows: [{ item: "lab1", grade: 9, hi: 10 }] },
      { key: "lin", total: 74, rows: [{ item: "quiz1", grade: 7, hi: 10 }, { item: "as1", grade: 72, hi: 100 }] },
      { key: "wri", total: 92, rows: [{ item: "as1", grade: 92, hi: 100 }] },
      { key: "pro", rows: [] }
    ],
    lastNight: { bed: "23:40", wake: "07:05", minutes: 445 },
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
  /* Proposals                                                           */
  /* ------------------------------------------------------------------ */

  var PROPOSALS = ["P1", "P2", "P3"];
  var BASE = "D3";
  var RECOMMENDED = "P2";

  // P1: one tint per section of the app.
  var SECTION_TONE = {
    picks: "violet", today: "peach", tasks: "mint", deadlines: "rose", courses: "sky",
    course: "sky", due: "rose", grades: "sand"
  };
  // P2: the faint watermark icon of each section card, and its colour.
  var WATERMARK = {
    picks: { icon: "spark", tone: "violet" }, today: { icon: "today", tone: "peach" },
    tasks: { icon: "tasks", tone: "mint" }, deadlines: { icon: "calendar", tone: "rose" }
  };
  // P3: the colour and illustration of each section card.
  var P3_SECTION = {
    picks: [{ tone: "violet", deco: "circles" }, { tone: "peach", deco: "arcs" }, { tone: "mint", deco: "stripes" }],
    today: { tone: "peach", deco: "sun" }, tasks: { tone: "mint", deco: "stripes" }, deadlines: { tone: "rose", deco: "dots" }
  };
  // Icon in front of a card heading (all proposals, not in D3).
  var SECTION_ICON = {
    picks: "spark", today: "today", tasks: "tasks", deadlines: "calendar", courses: "book",
    files: "file", links: "link", instructions: "note", submission: "upload"
  };

  /* ------------------------------------------------------------------ */
  /* State and URL handling                                              */
  /* ------------------------------------------------------------------ */

  // Screens a proposal redesigns; the others are drawn as in D3.
  var MAIN_SCREENS = ["today", "tasks", "courses", "course", "assignment"];
  var OPTIONS = {
    view: ["compare", "single"],
    prop: ["P1", "P2", "P3", "D3"],
    screen: MAIN_SCREENS,
    theme: ["light", "dark", "both"],
    lang: ["en", "tr"]
  };
  // Rows of the "one proposal" view.
  var SINGLE_ROWS = [
    MAIN_SCREENS,
    ["course-announcements", "course-assignments", "course-grades", "grades"],
    ["chatlist", "newchat", "coach", "chat", "sessions", "activity"]
  ];
  var RESTYLED = MAIN_SCREENS.concat(["course-announcements", "course-assignments", "course-grades", "grades"]);
  var ALIASES = { prop: { d: "D3" }, screen: { asg: "assignment" } };
  var state = { view: "compare", prop: RECOMMENDED, screen: "today", theme: "light", lang: "en" };

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
      var match = OPTIONS[key].filter(function (opt) { return opt.toLowerCase() === wanted; })[0];
      if (match) { state[key] = match; }
    });
  }

  function writeUrl() {
    var query = Object.keys(OPTIONS).map(function (key) { return key + "=" + state[key]; }).join("&");
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

  function fmtDate(date, opts, lang) { return new Intl.DateTimeFormat(LOCALE[lang], opts).format(date); }

  function fmtNum(value, lang, digits) {
    return new Intl.NumberFormat(LOCALE[lang], { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
  }

  function durText(minutes, lang) {
    var s = STRINGS[lang];
    var h = Math.floor(minutes / 60), m = minutes % 60, parts = [];
    if (h) { parts.push(h + " " + s.unitH); }
    if (m || !h) { parts.push(m + " " + s.unitMin); }
    return parts.join(" ");
  }

  function durHtml(minutes, lang) {
    var s = STRINGS[lang];
    var h = Math.floor(minutes / 60), m = minutes % 60, out = "";
    if (h) { out += '<span class="num">' + h + '</span><span class="unit">' + s.unitH + "</span>"; }
    if (m || !h) { out += '<span class="num">' + m + '</span><span class="unit">' + s.unitMin + "</span>"; }
    return '<span class="dur">' + out + "</span>";
  }

  /* ------------------------------------------------------------------ */
  /* Icons (inline SVG line icons, 24px grid, drawn for this project)    */
  /* ------------------------------------------------------------------ */

  var ICON_PATHS = {
    today: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
    chat: '<path d="M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-5 4v-4H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z"/>',
    tasks: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 12.2l2.8 2.8L16 9.2"/>',
    more: '<circle cx="5.5" cy="12" r="1.4" class="solid"/><circle cx="12" cy="12" r="1.4" class="solid"/><circle cx="18.5" cy="12" r="1.4" class="solid"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    up: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    compose: '<path d="M12 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19h11a1.5 1.5 0 0 0 1.5-1.5V12"/><path d="M17.6 3.6l2.8 2.8-8 8-3.4.6.6-3.4z"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
    search: '<circle cx="10.5" cy="10.5" r="6"/><path d="M15 15l5 5"/>',
    bell: '<path d="M6.5 17v-6a5.5 5.5 0 0 1 11 0v6M4.5 17h15M10 20h4"/>',
    pin: '<path d="M9 3.5h6l-1 6 3 3.5H7l3-3.5zM12 13v7.5"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3.2 2"/>',
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M18 3v4.4h-4.4"/>',
    download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14"/>',
    upload: '<path d="M12 15.5v-11M7.5 9L12 4.5 16.5 9M5 19.5h14"/>',
    chevron: '<path d="M9 5l7 7-7 7"/>',
    down: '<path d="M5 9l7 7 7-7"/>',
    file: '<path d="M7 3.5h6.5L18 8v11a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z"/><path d="M13.5 3.5V8H18"/>',
    note: '<path d="M7 3.5h10A1.5 1.5 0 0 1 18.5 5v14a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z"/><path d="M9 8.5h6M9 12h6M9 15.5h3.5"/>',
    link: '<path d="M10.5 13.5a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M13.5 10.5a4 4 0 0 0-5.7 0L5 13.3A4 4 0 0 0 10.7 19l1-1"/>',
    play: '<circle cx="12" cy="12" r="8.5"/><path d="M10.2 8.4l5.4 3.6-5.4 3.6z"/>',
    out: '<path d="M10 5.5H6.5A1.5 1.5 0 0 0 5 7v10.5A1.5 1.5 0 0 0 6.5 19H17a1.5 1.5 0 0 0 1.5-1.5V14"/><path d="M14 4.5h5.5V10M19.5 4.5l-8 8"/>',
    spark: '<path d="M12 4l2.1 5.9L20 12l-5.9 2.1L12 20l-2.1-5.9L4 12l5.9-2.1z"/>',
    folder: '<path d="M3.5 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2.5h8A1.5 1.5 0 0 1 20.5 9.5v8A1.5 1.5 0 0 1 19 19H5a1.5 1.5 0 0 1-1.5-1.5z"/>',
    calendar: '<rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/><circle cx="8.5" cy="14.5" r="1.1" class="solid"/>',
    book: '<path d="M12 6.5C10.3 5 7.8 4.5 4 4.5v14c3.8 0 6.3.5 8 2 1.7-1.5 4.2-2 8-2v-14c-3.8 0-6.3.5-8 2zM12 6.5v14"/>',
    megaphone: '<path d="M4 10v4a1 1 0 0 0 1 1h2.5L15 19V5L7.5 9H5a1 1 0 0 0-1 1zM18 9.5a3.5 3.5 0 0 1 0 5M8 15l1.2 4.5"/>',
    medal: '<circle cx="12" cy="14.5" r="5"/><path d="M9 10.2L6.5 3.5h4l1.5 4M15 10.2l2.5-6.7h-4L12 7.5"/>',
    // Course and project glyphs.
    wave: '<path d="M2.5 12c1.6-5.5 3.2-5.5 4.75 0s3.15 5.5 4.75 0 3.2-5.5 4.75 0 3.15 5.5 4.75 0"/>',
    chip: '<rect x="7" y="7" width="10" height="10" rx="1.5"/><path d="M10 7V4M14 7V4M10 20v-3M14 20v-3M7 10H4M7 14H4M20 10h-3M20 14h-3"/>',
    matrix: '<path d="M7.5 4.5h-3v15h3M16.5 4.5h3v15h-3"/><circle cx="9.5" cy="9" r="1.1" class="solid"/><circle cx="14.5" cy="9" r="1.1" class="solid"/><circle cx="9.5" cy="15" r="1.1" class="solid"/><circle cx="14.5" cy="15" r="1.1" class="solid"/>',
    pen: '<path d="M12 3.5l5.5 8.5L12 20.5 6.5 12z"/><path d="M12 20.5V13.5"/><circle cx="12" cy="12" r="1.5"/>',
    dice: '<rect x="4.5" y="4.5" width="15" height="15" rx="3.5"/><circle cx="9" cy="9" r="1.2" class="solid"/><circle cx="15" cy="15" r="1.2" class="solid"/><circle cx="12" cy="12" r="1.2" class="solid"/>'
  };

  function icon(name, extraClass) {
    return '<svg class="icon ' + (extraClass || "") + '" viewBox="0 0 24 24" aria-hidden="true" focusable="false">' +
      ICON_PATHS[name] + "</svg>";
  }

  /* ------------------------------------------------------------------ */
  /* Decorations: watermarks (P2) and geometric illustrations (P3)       */
  /* ------------------------------------------------------------------ */

  /** A large, faint icon in the corner of a card. Purely decorative. */
  function watermark(name) { return '<span class="wm" aria-hidden="true">' + icon(name) + "</span>"; }

  /**
   * A small illustration made of plain shapes, in the card's colour:
   * .d1 is the card's second tint, .d2 its ink at low opacity, .d3 an ink
   * outline. Drawn on a w×h box; no text sits on it.
   */
  function deco(kind, w, h, extraClass) {
    var out = "", i, j;
    function c(cx, cy, r, cls) { return '<circle class="' + cls + '" cx="' + cx + '" cy="' + cy + '" r="' + r + '"/>'; }
    if (kind === "circles") {
      out += c(w - 34, h * 0.35, h * 0.62, "d1") + c(w - 78, h * 0.95, h * 0.38, "d2") + c(w - 18, h * 0.9, h * 0.2, "d3");
      for (i = 0; i < 4; i += 1) { out += c(w - 128 + i * 9, 12, 1.6, "d2"); }
    } else if (kind === "arcs") {
      for (i = 0; i < 4; i += 1) {
        var r = 12 + i * 11;
        out += '<path class="d3" d="M' + (w - r) + "," + h + "A" + r + "," + r + " 0 0 1 " + w + "," + (h - r) + '"/>';
      }
      out += c(w - 70, h * 0.32, 7, "d1");
    } else if (kind === "stripes") {
      out += '<rect class="d1" x="' + (w - 70) + '" y="' + (h * 0.18) + '" width="' + (h * 0.7) + '" height="' + (h * 0.7) + '" rx="' + (h * 0.35) + '"/>';
      for (i = 0; i < 6; i += 1) {
        out += '<path class="d3" d="M' + (w - 120 + i * 9) + "," + h + "l" + (h * 0.6) + "," + (-h) + '"/>';
      }
    } else if (kind === "dots") {
      for (i = 0; i < 4; i += 1) {
        for (j = 0; j < 7; j += 1) { out += c(w - 12 - j * 11, 9 + i * 11, 1.8, j + i < 5 ? "d2" : "d1"); }
      }
      out += c(w - 26, h - 6, 14, "d1");
    } else if (kind === "sun") {
      out += c(w - 40, h + 4, 22, "d1");
      for (i = 0; i < 5; i += 1) {
        var a = Math.PI * (0.12 + i * 0.19);
        out += '<path class="d3" d="M' + (w - 40 - Math.cos(a) * 30).toFixed(1) + "," + (h + 4 - Math.sin(a) * 30).toFixed(1) +
          "L" + (w - 40 - Math.cos(a) * 40).toFixed(1) + "," + (h + 4 - Math.sin(a) * 40).toFixed(1) + '"/>';
      }
    } else if (kind === "waves") {
      for (i = 0; i < 3; i += 1) {
        var y = 8 + i * 9, d = "M" + (w - 170) + "," + y;
        for (j = 0; j < 6; j += 1) { d += " q7,-8 14,0 t14,0"; }
        out += '<path class="' + (i === 1 ? "d3" : "d2l") + '" d="' + d + '"/>';
      }
      out += c(w - 24, 14, 12, "d1");
    } else if (kind === "grid") {
      for (i = 0; i < 3; i += 1) {
        for (j = 0; j < 5; j += 1) {
          out += '<rect class="' + ((i + j) % 3 === 0 ? "d1" : "d3") + '" x="' + (w - 22 - j * 16) + '" y="' + (6 + i * 16) + '" width="10" height="10" rx="2"/>';
        }
      }
    } else if (kind === "triangles") {
      for (i = 0; i < 5; i += 1) {
        var x = w - 30 - i * 22;
        out += '<path class="' + (i % 2 ? "d3" : "d1") + '" d="M' + x + "," + (h - 6) + "l10,-17 10,17z" + '"/>';
      }
      out += c(w - 140, 14, 5, "d2");
    }
    return '<svg class="deco ' + (extraClass || "") + '" viewBox="0 0 ' + w + " " + h + '" preserveAspectRatio="xMaxYMid slice" aria-hidden="true" focusable="false">' +
      out + "</svg>";
  }

  /* ------------------------------------------------------------------ */
  /* Tone helpers                                                        */
  /* ------------------------------------------------------------------ */

  function tone(name) { return name ? " tone-" + name : ""; }
  function ink(name) { return name ? " ink-" + name : ""; }

  /** Colour of a card that belongs to a section of the app. */
  function sectionTone(p, role) {
    if (p === "P1") { return SECTION_TONE[role] || ""; }
    if (p === "P3" && P3_SECTION[role] && P3_SECTION[role].tone) { return P3_SECTION[role].tone; }
    return "";
  }

  /** Colour of a card that belongs to one course (P2 and P3), or to its section (P1). */
  function courseTone(p, ctx, role) {
    if (p === "P2" || p === "P3") { return CTX[ctx].tone; }
    return sectionTone(p, role);
  }

  function shaped(p, kind) { return p === "P3" ? " shape-" + kind : ""; }

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

  function topBar(s, title, opts) {
    opts = opts || {};
    var html = '<header class="topbar">';
    if (opts.back) {
      html += '<span class="tb-btn tb-back" role="button" aria-label="' + esc(s.back) + '">' + icon("back") + "</span>";
    }
    if (opts.mark) { html += '<span class="thumb is-featured tb-mark" aria-hidden="true">' + icon(opts.mark) + "</span>"; }
    html += '<div class="tb-titles"><h2 class="tb-title">' + esc(title) + "</h2>" +
      (opts.sub ? '<span class="tb-sub">' + esc(opts.sub) + "</span>" : "") + "</div>";
    html += '<span class="tb-spacer"></span>';
    html += '<span class="status" role="button" title="' + esc(s.connectedLong) + '" aria-label="' + esc(s.connectedLong) + '">' +
      '<span class="status-dot" aria-hidden="true"></span><span class="status-lbl">' + esc(s.connected) + "</span></span>";
    if (opts.pill) { html += '<span class="tb-pill" role="button">' + esc(opts.pill) + "</span>"; }
    (opts.icons || []).forEach(function (name) {
      var label = { bell: s.reminders, clock: s.sessions, compose: s.newChat, search: s.search, refresh: s.refresh }[name];
      var count = name === "bell" ? opts.bellCount : 0;
      if (count) { label += " (" + s.newReminders(count) + ")"; }
      html += '<span class="tb-icon" role="button" aria-label="' + esc(label) + '" title="' + esc(label) + '">' + icon(name) +
        (count ? badge(count, "badge-corner") : "") + "</span>";
    });
    return html + "</header>";
  }

  function badge(count, extraClass) {
    return '<span class="badge num ' + (extraClass || "") + '" aria-hidden="true">' + count + "</span>";
  }

  function fab(s) {
    return '<span class="fab" role="button" aria-label="' + esc(s.capture) + '" title="' + esc(s.capture) + '">' + icon("plus") + "</span>";
  }

  function bottomBar(s, current) {
    var html = '<nav class="bottombar" aria-label="' + esc(s.nav) + '">';
    ["today", "chat", "tasks", "more"].forEach(function (key, i) {
      var on = key === current;
      var count = key === "chat" && !on ? DATA.unreadChat : 0;
      var name = s[key] + (count ? " (" + s.unread(count) + ")" : "");
      if (i === 2) { html += fab(s); }
      html += '<span class="nav-item' + (on ? " on" : "") + '" role="link" aria-label="' + esc(name) + '" title="' + esc(name) + '"' +
        (on ? ' aria-current="page"' : "") + '><span class="nav-ind">' + icon(key) + (count ? badge(count, "badge-corner") : "") + "</span></span>";
    });
    return html + '<span class="gesture" aria-hidden="true"></span></nav>';
  }

  /** Heading row of a section or card; the icon tile is shown in the proposals only. */
  function head(p, label, note, iconName) {
    return '<div class="sec-head">' + (p !== BASE && iconName ? '<span class="sec-ico" aria-hidden="true">' + icon(iconName) + "</span>" : "") +
      "<h3>" + esc(label) + '</h3><span class="sec-rule" aria-hidden="true"></span>' +
      (note ? '<span class="sec-meta">' + note + "</span>" : "") + "</div>";
  }

  function section(p, label, note, body, extraClass, iconName) {
    return '<section class="sec ' + (extraClass || "") + '">' + head(p, label, note, iconName) + body + "</section>";
  }

  /** A section drawn as one visible card, heading included. */
  function panel(p, label, note, body, extraClass, iconName, decoration) {
    return '<section class="panel ' + (extraClass || "") + '">' + (decoration || "") + head(p, label, note, iconName) + body + "</section>";
  }

  /** The context of a task: plain text, or in P2 a small label in the course's colour. */
  function ctxLabel(p, s, ctx) {
    if (!ctx) { return ""; }
    if (p === "P2") {
      return '<span class="ctx' + tone(CTX[ctx].tone) + '">' + icon(CTX[ctx].glyph) + '<span class="clip">' + esc(s.ctx[ctx]) + "</span></span>";
    }
    return '<span aria-hidden="true">·</span><span class="clip">' + esc(s.ctx[ctx]) + "</span>";
  }

  function taskRow(p, s, t) {
    var cls = "row task" + (t.done ? " done" : "") + (t.overdue ? " overdue" : "") + (p === "P2" && t.ctx ? ink(CTX[t.ctx].tone) : "");
    return '<div class="' + cls + '"><span class="check" role="checkbox" aria-checked="' + (t.done ? "true" : "false") +
      '" aria-label="' + esc(s[t.title]) + '">' + (t.done ? icon("check") : "") + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s">' +
      (t.overdue ? '<span class="tag tag-alert"><span class="tag-mark" aria-hidden="true">!</span>' + esc(s.overdue) + "</span>" : "") +
      "<span>" + esc(s.when[t.when]) + "</span>" + ctxLabel(p, s, t.ctx) + "</div></div></div>";
  }

  /** A round mark with the course's icon. */
  function courseMark(p, ctx, extraClass) {
    var tint = p === "P2" || p === "P3" ? ink(CTX[ctx].tone) : "";
    return '<span class="thumb' + tint + " " + (extraClass || "") + '" aria-hidden="true">' + icon(CTX[ctx].glyph) + "</span>";
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Today                                                       */
  /* ------------------------------------------------------------------ */

  function coachCard(s) {
    // Unchanged from D3 in every proposal.
    return '<section class="card advice" role="link"><div class="advice-k">' +
      '<span class="coach-tag">' + esc(s.coach) + "</span><span>" + esc(s.adviceLabel) + "</span>" +
      '<span class="advice-open">' + esc(s.openConversation) + icon("chevron") + "</span></div>" +
      '<p class="advice-t">' + esc(s.adviceText) + "</p>" +
      '<p class="advice-why"><b>' + esc(s.why) + "</b> " + esc(s.adviceWhy) + "</p></section>";
  }

  function todayCard(p, s) {
    var list = DATA.today;
    var tasks = list.map(function (t) { return taskRow(p, s, t); }).join("");
    var note = esc(s.todayNote(list.length, list.filter(function (t) { return t.done; }).length));
    var more = list.length - DATA.todayVisible;
    var cls = "sec-today" + tone(sectionTone(p, "today")) + shaped(p, "leaf") + (p === "P2" ? ink(WATERMARK.today.tone) + " has-wm" : "");
    var decoration = p === "P2" ? watermark(WATERMARK.today.icon) : p === "P3" ? deco("sun", 150, 44, "deco-corner") : "";
    return panel(p, s.secToday, note,
      '<div class="scroll-box"><div class="scroll-list" tabindex="0" role="group" aria-label="' + esc(s.secToday) + '">' + tasks + "</div>" +
      '<span class="scroll-fade" aria-hidden="true"></span></div>' +
      '<div class="scroll-more">' + esc(s.moreCount(more)) + icon("down") + "</div>", cls, SECTION_ICON.today, decoration);
  }

  function screenToday(p, lang) {
    var s = STRINGS[lang];
    var picks;
    if (p === "P3") {
      // Picks as tiles: the first one wide, the other two side by side.
      var tiles = DATA.top.map(function (t, i) {
        var look = P3_SECTION.picks[i];
        return '<div class="pick-tile' + (i === 0 ? " pick-wide shape-leaf" : "") + tone(look.tone) + '">' +
          deco(look.deco, i === 0 ? 200 : 120, i === 0 ? 72 : 52, "deco-tile") +
          '<span class="pick-n num" aria-hidden="true">' + (i + 1) + "</span>" +
          '<div class="pick-t">' + esc(s[t.title]) + "</div>" +
          '<div class="pick-s">' + esc(s[t.why]) + "</div></div>";
      });
      picks = '<section class="sec sec-top picks-tiles">' + head(p, s.secTop, "", SECTION_ICON.picks) +
        tiles[0] + '<div class="pick-pair">' + tiles[1] + tiles[2] + "</div></section>";
    } else {
      var top = DATA.top.map(function (t, i) {
        return '<div class="row pick' + (p === "P2" ? ink(CTX[t.ctx].tone) : "") + '"><span class="rank num" aria-hidden="true">' + (i + 1) + "</span>" +
          '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s"><span>' + esc(s[t.why]) + "</span></div></div></div>";
      }).join("");
      picks = panel(p, s.secTop, "", '<div class="list">' + top + "</div>",
        "sec-top" + tone(sectionTone(p, "picks")) + (p === "P2" ? ink(WATERMARK.picks.tone) + " has-wm" : ""),
        SECTION_ICON.picks, p === "P2" ? watermark(WATERMARK.picks.icon) : "");
    }
    return topBar(s, s.today, { icons: ["bell"], bellCount: DATA.newReminders }) +
      '<div class="content content-today">' + coachCard(s) + picks + todayCard(p, s) + "</div>" + bottomBar(s, "today");
  }

  /* ------------------------------------------------------------------ */
  /* Screen: Tasks                                                       */
  /* ------------------------------------------------------------------ */

  function screenTasks(p, lang) {
    var s = STRINGS[lang];
    var list = DATA.important;
    var body;
    if (p === "P3") {
      // The first task as a large card, the rest on a ticket-edged card.
      var first = list[0];
      var hero = '<section class="panel hero-task shape-leaf' + tone(P3_SECTION.tasks.tone) + '">' + deco(P3_SECTION.tasks.deco, 220, 64, "deco-band") +
        '<div class="hero-k">' + icon("tasks") + "<span>" + esc(s.upNext) + "</span></div>" +
        '<div class="hero-t">' + esc(s[first.title]) + "</div>" +
        '<div class="hero-row"><span class="check" role="checkbox" aria-checked="false" aria-label="' + esc(s[first.title]) + '"></span>' +
        '<span class="hero-s">' + esc(s.when[first.when]) + " · " + esc(s.ctx[first.ctx]) + "</span></div></section>";
      var rest = list.slice(1).map(function (t) { return taskRow(p, s, t); }).join("");
      body = hero + panel(p, s.secImportant, esc(s.importantNote), '<div class="list">' + rest + "</div>",
        "sec-important shape-ticket", SECTION_ICON.tasks, '<span class="notch notch-l" aria-hidden="true"></span><span class="notch notch-r" aria-hidden="true"></span>');
    } else {
      var rows = '<div class="list">' + list.map(function (t) { return taskRow(p, s, t); }).join("") + "</div>";
      body = p === BASE ? section(p, s.secImportant, esc(s.importantNote), '<div class="card list">' + rows + "</div>", "sec-important") :
        panel(p, s.secImportant, esc(s.importantNote), rows,
          "sec-important" + tone(sectionTone(p, "tasks")) + (p === "P2" ? ink(WATERMARK.tasks.tone) + " has-wm" : ""),
          SECTION_ICON.tasks, p === "P2" ? watermark(WATERMARK.tasks.icon) : "");
    }
    return topBar(s, s.tasks, { icons: ["search"] }) +
      '<div class="content content-tasks">' + body +
      '<span class="btn btn-wide" role="button">' + esc(s.showAll(DATA.openTasks)) + "</span>" +
      '<p class="foot-note">' + esc(s.tasksFoot(list.length, DATA.openTasks)) + "</p>" +
      "</div>" + bottomBar(s, "tasks");
  }

  /* ------------------------------------------------------------------ */
  /* Screens: Courses, course page, assignment, all grades               */
  /* ------------------------------------------------------------------ */

  function shortDate(offset, lang) { return fmtDate(addDays(TODAY, offset), { day: "numeric", month: "short" }, lang); }
  function longDate(offset, lang) { return fmtDate(addDays(TODAY, offset), { day: "numeric", month: "long" }, lang); }
  function dayDate(offset, lang) { return fmtDate(addDays(TODAY, offset), { weekday: "short", day: "numeric", month: "short" }, lang); }
  function fileSize(mb, lang) { return mb < 1 ? Math.round(mb * 1000) + " KB" : fmtNum(mb, lang, 1) + " MB"; }

  function newBadge(s, n) {
    return '<span class="badge num" role="img" aria-label="' + esc(s.newCount(n)) + '" title="' + esc(s.newCount(n)) + '">' + n + "</span>";
  }

  function screenCourses(p, lang) {
    var s = STRINGS[lang];
    var soon = DATA.courses.filter(function (c) { return c.next; })
      .sort(function (a, b) { return a.inDays - b.inDays; }).slice(0, 3);

    // Summary card: the nearest deadlines only; each opens the assignment.
    var summary;
    if (p === "P3") {
      var tiles = soon.map(function (c) {
        return '<div class="dl-tile' + ink(CTX[c.key].tone) + '" role="link"><span class="dl-n num">' + c.inDays + '</span><span class="dl-u">' + esc(s.days) + "</span>" +
          '<span class="dl-t">' + esc(s.item[c.next]) + '</span><span class="dl-c">' + esc(s.ctx[c.key]) + "</span></div>";
      }).join("");
      summary = panel(p, s.secDeadlines, "", '<div class="dl-tiles">' + tiles + "</div>",
        "sec-deadlines shape-leaf" + tone(P3_SECTION.deadlines.tone), SECTION_ICON.deadlines, deco(P3_SECTION.deadlines.deco, 110, 46, "deco-corner"));
    } else {
      var deadlines = soon.map(function (c) {
        return '<div class="row plain deadline" role="link">' + (p === "P2" ? courseMark(p, c.key, "thumb-sm") : "") +
          '<div class="main"><div class="row-t">' + esc(s.item[c.next]) + '</div><div class="row-s"><span class="clip">' +
          esc(s.ctx[c.key]) + '</span></div></div><span class="due num">' + esc(s.dueIn(c.inDays)) + "</span>" +
          '<span class="row-icon" aria-hidden="true">' + icon("chevron") + "</span></div>";
      }).join("");
      summary = panel(p, s.secDeadlines, esc(s.deadlinesNote), '<div class="list">' + deadlines + "</div>",
        "sec-deadlines" + tone(sectionTone(p, "deadlines")) + (p === "P2" ? ink(WATERMARK.deadlines.tone) + " has-wm" : ""),
        SECTION_ICON.deadlines, p === "P2" ? watermark(WATERMARK.deadlines.icon) : "");
    }

    function sub(c) { return c.next ? s.next + " " + s.item[c.next] + " · " + s.dueIn(c.inDays) : s.noDeadline; }
    var list;
    if (p === "P2") {
      // One tile per course, in the course's colour, with its icon as a watermark.
      list = section(p, s.secCourses, esc(s.courseCount(DATA.courses.length)), '<div class="course-tiles">' +
        DATA.courses.map(function (c) {
          return '<div class="course-tile has-wm' + tone(CTX[c.key].tone) + '" role="link">' + watermark(CTX[c.key].glyph) +
            courseMark(p, c.key) + '<div class="main"><div class="row-t">' + esc(s.ctx[c.key]) + '</div><div class="row-s"><span class="clip">' + esc(sub(c)) + "</span></div></div>" +
            (c.fresh ? newBadge(s, c.fresh) : "") + '<span class="row-icon" aria-hidden="true">' + icon("chevron") + "</span></div>";
        }).join("") + "</div>", "sec-courses", SECTION_ICON.courses);
    } else if (p === "P3") {
      // A grid of tiles, each with its course's pattern; the most urgent one is wide.
      list = section(p, s.secCourses, esc(s.courseCount(DATA.courses.length)), '<div class="course-grid">' +
        DATA.courses.map(function (c, i) {
          return '<div class="course-cell' + (i === 0 ? " cell-wide" : "") + tone(CTX[c.key].tone) + '" role="link">' +
            '<div class="cell-art">' + deco(CTX[c.key].deco, i === 0 ? 320 : 160, 40) + courseMark(p, c.key, "thumb-sm") +
            (c.fresh ? newBadge(s, c.fresh) : "") + "</div>" +
            '<div class="cell-t">' + esc(s.ctx[c.key]) + '</div><div class="cell-s">' + esc(c.next ? s.item[c.next] + " · " + s.dueIn(c.inDays) : s.noDeadline) + "</div></div>";
        }).join("") + "</div>", "sec-courses", SECTION_ICON.courses);
    } else {
      var rows = DATA.courses.map(function (c) {
        return '<div class="row course" role="link">' + courseMark(p, c.key) +
          '<div class="main"><div class="row-t">' + esc(s.ctx[c.key]) + '</div><div class="row-s"><span class="clip">' + esc(sub(c)) + "</span></div></div>" +
          (c.fresh ? newBadge(s, c.fresh) : "") + '<span class="row-icon" aria-hidden="true">' + icon("chevron") + "</span></div>";
      }).join("");
      list = p === BASE ? section(p, s.secCourses, esc(s.courseCount(DATA.courses.length)), '<div class="card list">' + rows + "</div>", "sec-courses") :
        panel(p, s.secCourses, esc(s.courseCount(DATA.courses.length)), '<div class="list">' + rows + "</div>",
          "sec-courses" + tone(sectionTone(p, "courses")), SECTION_ICON.courses);
    }

    return topBar(s, s.courses, { back: true, sub: s.lastSync, pill: s.allGrades, icons: ["refresh"] }) +
      '<div class="content content-courses">' + summary + list + "</div>" + bottomBar(s, "more");
  }

  function gradeRow(s, g) {
    var graded = typeof g.grade === "number";
    return '<div class="row plain grade"><div class="main"><div class="row-t">' + esc(s.item[g.item]) + '</div><div class="row-s"><span>' +
      esc(graded ? s.range(0, g.hi) : s.notGraded) + '</span></div></div><span class="grade-v num' + (graded ? "" : " is-empty") + '">' +
      (graded ? g.grade : "–") + "</span></div>";
  }

  function fileRow(p, s, lang, f, ctx) {
    var meta = f.type + " · " + fileSize(f.mb, lang) + " · " + shortDate(f.day, lang);
    var tint = p === "P2" || p === "P3" ? ink(CTX[ctx].tone) : "";
    return '<div class="row file"><span class="thumb' + tint + '" aria-hidden="true">' + icon("file") + "</span>" +
      '<div class="main"><div class="row-t"><span class="clip">' + esc(s.file[f.name]) + "</span>" +
      (f.fresh ? '<span class="new-tag">' + esc(s.newTag) + "</span>" : "") + "</div>" +
      '<div class="row-s">' + (f.saved ? '<span class="saved">' + icon("check") + esc(s.saved) + "</span>" : "<span>" + esc(meta) + "</span>") + "</div></div>" +
      '<span class="icon-btn' + (f.saved ? " is-done" : "") + '" role="button" aria-label="' + esc((f.saved ? s.downloaded : s.download) + ": " + s.file[f.name]) + '" title="' +
      esc(f.saved ? s.downloaded : s.download) + '">' + icon(f.saved ? "check" : "download") + "</span></div>";
  }

  var COURSE_TABS = ["tabMaterials", "tabAnnouncements", "tabAssignments", "tabGrades"];

  /** Card class for the course page: sky in P1, neutral in P2, cut-corner neutral in P3. */
  function courseCard(p) { return (p === "P1" ? tone("sky") : "") + shaped(p, "leaf"); }

  function courseTab(p, s, lang, tab) {
    var ctx = DATA.courses[0].key;
    var cls = courseCard(p);
    if (tab === 0) {
      var sections = DATA.materials.map(function (m) {
        var files = m.files.map(function (f) { return fileRow(p, s, lang, f, ctx); }).join("");
        return p === BASE ? section(p, s.week(m.week, s.weekTitle[m.title]), "", '<div class="card list">' + files + "</div>", "sec-files") :
          panel(p, s.week(m.week, s.weekTitle[m.title]), "", '<div class="list">' + files + "</div>", "sec-files" + cls);
      }).join("");
      var links = DATA.links.map(function (l) {
        var video = l.kind === "video";
        return '<div class="row file link-row" role="link"><span class="thumb' + (p === "P2" || p === "P3" ? ink(CTX[ctx].tone) : "") + '" aria-hidden="true">' + icon(video ? "play" : "link") + "</span>" +
          '<div class="main"><div class="row-t">' + esc(s.link[l.name]) + '</div><div class="row-s"><span>' +
          esc(video ? s.video + " · " + durText(l.minutes, lang) : s.externalLink) + "</span></div></div>" +
          '<span class="row-icon" role="img" aria-label="' + esc(s.open) + '">' + icon("out") + "</span></div>";
      }).join("");
      return sections + (p === BASE ? section(p, s.links, "", '<div class="card list">' + links + "</div>", "sec-files sec-links") :
        panel(p, s.links, "", '<div class="list">' + links + "</div>", "sec-files sec-links" + cls, SECTION_ICON.links));
    }
    if (tab === 1) {
      return DATA.announcements.map(function (a) {
        var body = s.ann[a.key + "Body"](typeof a.dueDay === "number" ? longDate(a.dueDay, lang) : "");
        return '<article class="panel ann' + cls + '"><div class="ann-head">' + (p !== BASE ? '<span class="sec-ico" aria-hidden="true">' + icon("megaphone") + "</span>" : "") +
          '<h3 class="row-t">' + esc(s.ann[a.key]) + "</h3>" +
          (a.fresh ? '<span class="new-tag">' + esc(s.newTag) + "</span>" : "") +
          '<span class="ann-date num">' + esc(shortDate(a.day, lang)) + "</span></div>" +
          '<p class="ann-body">' + esc(body) + "</p></article>";
      }).join("");
    }
    if (tab === 2) {
      var rows = DATA.assignments.map(function (a) {
        var when = s.due(shortDate(a.day, lang)) + (a.day > 0 ? " · " + s.dueIn(a.day) : "");
        return '<div class="row plain assignment" role="link"><div class="main"><div class="row-t">' + esc(s.item[a.item]) + '</div><div class="row-s"><span>' + esc(when) + "</span></div></div>" +
          statusChip(s, a) + "</div>";
      }).join("");
      return '<section class="panel' + cls + '"><div class="list">' + rows + "</div></section>";
    }
    var total = DATA.courses[0].total;
    return '<section class="panel total' + cls + '"><div class="row plain grade"><div class="main"><div class="row-t">' + esc(s.courseTotal) + '</div><div class="row-s"><span>' +
      esc(s.range(0, 100)) + '</span></div></div><span class="grade-v grade-total num">' + total + "</span></div></section>" +
      '<section class="panel' + cls + '"><div class="list">' + DATA.courseGrades.map(function (g) { return gradeRow(s, g); }).join("") + "</div></section>";
  }

  function statusChip(s, a) {
    return '<span class="chip chip-' + a.status + '"><span class="chip-mark" aria-hidden="true">' + (a.status === "submitted" || a.status === "graded" ? icon("check") : "") + "</span>" +
      esc(s.st[a.status]) + (a.status === "graded" ? ' <span class="num">' + a.grade + "/" + a.hi + "</span>" : "") + "</span>";
  }

  /** P2 and P3: a header card in the course's colour above the tabs. */
  function courseHero(p, s, lang) {
    var c = DATA.courses[0];
    var t = CTX[c.key];
    var text = '<div class="main"><div class="hero-k">' + esc(s.next) + " " + esc(s.item[c.next]) + '</div><div class="row-s"><span>' +
      esc(s.due(dayDate(c.inDays, lang))) + " · " + esc(s.dueIn(c.inDays)) + "</span></div></div>" +
      newBadge(s, c.fresh);
    if (p === "P2") {
      return '<section class="course-hero has-wm' + tone(t.tone) + '">' + watermark(t.glyph) + courseMark(p, c.key) + text + "</section>";
    }
    return '<section class="course-hero hero-art shape-leaf' + tone(t.tone) + '">' + deco(t.deco, 358, 46, "deco-band") +
      '<div class="hero-line">' + courseMark(p, c.key) + text + "</div></section>";
  }

  function screenCourse(tab) {
    return function (p, lang) {
      var s = STRINGS[lang];
      var tabs = '<div class="tabs tabs-4" role="tablist">' + COURSE_TABS.map(function (key, i) {
        return '<span class="tab' + (i === tab ? " on" : "") + '" role="tab" aria-selected="' + (i === tab) + '">' + esc(s[key]) + "</span>";
      }).join("") + "</div>";
      var hero = p === "P2" || p === "P3" ? courseHero(p, s, lang) : "";
      return topBar(s, s.ctx[DATA.courses[0].key], { back: true, icons: ["refresh"] }) +
        '<div class="content content-course">' + hero + tabs + courseTab(p, s, lang, tab) + "</div>" + bottomBar(s, "more");
    };
  }

  /** The assignment detail: dates and status, instructions, files, submission. */
  function screenAssignment(p, lang) {
    var s = STRINGS[lang];
    var a = DATA.assignment;
    var t = CTX[a.ctx];
    var dueText = dayDate(a.due, lang) + " · " + a.time;
    var status = statusChip(s, { status: "open" });

    var dueCard;
    var dueInner = '<div class="due-k">' + esc(s.dueLabel) + '</div><div class="due-v num">' + esc(dueText) + "</div>" +
      '<div class="due-s"><span>' + esc(s.dueIn(a.due)) + '</span><span aria-hidden="true">·</span><span>' + esc(s.opened(shortDate(a.opened, lang))) + "</span></div>";
    if (p === "P3") {
      // A ticket: the date on the left, the days left on the stub, notches between.
      dueCard = '<section class="panel due-card ticket' + tone(t.tone) + '"><div class="ticket-main">' + dueInner + status + "</div>" +
        '<div class="ticket-stub">' + deco(t.deco, 90, 120, "deco-stub") + '<span class="dl-n num">' + a.due + '</span><span class="dl-u">' + esc(s.days) + "</span></div>" +
        '<span class="notch notch-t" aria-hidden="true"></span><span class="notch notch-b" aria-hidden="true"></span></section>';
    } else {
      var dueTone = p === "P1" ? tone(SECTION_TONE.due) : p === "P2" ? tone(t.tone) + " has-wm" : "";
      dueCard = '<section class="panel due-card' + dueTone + '">' + (p === "P2" ? watermark(t.glyph) : "") +
        (p === "P2" ? '<div class="due-ctx">' + icon(t.glyph) + esc(s.ctx[a.ctx]) + "</div>" : "") +
        '<div class="due-line"><div class="main">' + dueInner + "</div>" + status + "</div></section>";
    }

    var cls = (p === "P1" ? tone("sky") : "") + shaped(p, "leaf");
    var info = panel(p, s.instructions, "", '<p class="asg-text">' + esc(s.asgText) + "</p>", "sec-info" + cls, SECTION_ICON.instructions);
    var files = panel(p, s.files, "", '<div class="list">' + a.files.map(function (f) { return fileRow(p, s, lang, f, a.ctx); }).join("") + "</div>",
      "sec-files" + cls, SECTION_ICON.files);
    var kv = function (k, v) { return '<div class="kv"><span class="kv-k">' + esc(k) + '</span><span class="kv-v">' + v + "</span></div>"; };
    var sub = panel(p, s.submission, "",
      kv(s.format, esc(s.formatText)) + kv(s.grade, esc(s.notGraded) + " · " + esc(s.range(0, a.hi))) +
      '<div class="row plain task-link" role="link"><span class="thumb thumb-sm' + (p === "P2" || p === "P3" ? ink(t.tone) : "") + '" aria-hidden="true">' + icon("tasks") + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s.inTasks) + '</div><div class="row-s"><span class="clip">' + esc(s.inTasksSub(shortDate(a.due, lang))) + "</span></div></div>" +
      '<span class="row-icon" aria-hidden="true">' + icon("chevron") + "</span></div>",
      "sec-sub" + cls, SECTION_ICON.submission);

    return topBar(s, s.item[a.item], { back: true, sub: s.ctx[a.ctx], icons: ["refresh"] }) +
      '<div class="content content-asg">' + dueCard + info + files + sub +
      '<span class="btn btn-wide" role="button">' + icon("out") + esc(s.openSubmit) + "</span>" +
      "</div>" + bottomBar(s, "more");
  }

  function screenGrades(p, lang) {
    var s = STRINGS[lang];
    var groups = DATA.allGrades.map(function (c) {
      var body = c.rows.length ? c.rows.map(function (g) { return gradeRow(s, g); }).join("") : '<p class="empty-line">' + esc(s.noGrades) + "</p>";
      return panel(p, s.ctx[c.key], typeof c.total === "number" ? esc(s.total) + ' <b class="num">' + c.total + "</b>" : "",
        '<div class="list">' + body + "</div>", "sec-grades" + tone(courseTone(p, c.key, "grades")) + shaped(p, "leaf"), p === BASE ? "" : CTX[c.key].glyph);
    }).join("");
    return topBar(s, s.allGrades, { back: true }) +
      '<div class="content content-grades">' + groups + "</div>" + bottomBar(s, "more");
  }

  /* ------------------------------------------------------------------ */
  /* Screens kept as in D3: Chat list, new chat, coach, conversation,    */
  /* Sessions, Activity                                                  */
  /* ------------------------------------------------------------------ */

  function composer(s) {
    return '<div class="composer"><div class="field"><span class="placeholder">' + esc(s.composer) + "</span></div>" +
      '<span class="send" role="button" aria-label="' + esc(s.send) + '">' + icon("up") + "</span></div>";
  }

  function screenChat(p, lang) {
    var s = STRINGS[lang];
    function userMsg(text) { return '<div class="msg msg-user"><div class="bubble"><p>' + esc(text) + "</p></div></div>"; }
    var answer = '<div class="msg msg-bot"><div class="bubble">' +
      "<p>" + esc(s.chatA1) + "</p><ul><li>" + esc(s.chatLi1) + "</li><li>" + esc(s.chatLi2) + "</li><li>" + esc(s.chatLi3) + "</li></ul>" +
      "<p>" + esc(s.chatA2) + "</p>" +
      '<div class="code"><pre><code><span class="c-dim">$</span> ls notes/alpha\noutline.md   check-in.md</code></pre></div></div></div>';
    var ap = DATA.approval;
    var approval = '<div class="ask-card approval" role="group" aria-label="' + esc(s.approvalLabel) + '">' +
      '<div class="ask-k">' + esc(s.approvalLabel) + "</div>" +
      '<p class="ask-t">' + esc(s.approvalText[0]) + '<span class="path">' + esc(ap.path) + "</span>" + esc(s.approvalText[1]) + "</p>" +
      '<p class="ask-files path">' + ap.files.map(esc).join(" · ") + "</p>" +
      '<div class="ask-acts"><span class="btn" role="button">' + esc(s.allow) + "</span>" +
      '<span class="btn btn-quiet" role="button">' + esc(s.deny) + "</span></div>" +
      '<p class="ask-note">' + esc(s.keptNote) + "</p></div>";
    return topBar(s, s.chat, { back: true, icons: ["clock", "compose"] }) +
      '<div class="content content-chat"><div class="thread">' + userMsg(s.chatQ1) + answer + userMsg(s.chatQ2) + approval + "</div></div>" +
      composer(s) + bottomBar(s, "chat");
  }

  function chatListBody(s) {
    var coach = '<section class="panel coach-pin" role="link"><div class="row session">' +
      '<span class="thumb is-featured" aria-hidden="true">' + icon("spark") + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s.coach) + '</div><div class="row-s"><span class="clip">' + esc(s.coachQ) + "</span></div>" +
      '<div class="row-s row-usage"><span>' + esc(s.usageToday(DATA.coachRuns)) + "</span></div></div>" +
      '<span class="row-side"><span class="row-when num">08:30</span>' +
      '<span class="row-icon row-pin" role="img" aria-label="' + esc(s.pinned) + '" title="' + esc(s.pinned) + '">' + icon("pin") + "</span></span></div></section>";
    var rows = DATA.recent.map(function (ses) { return sessionRow(s, ses, "", false); }).join("");
    return coach +
      section(BASE, s.recent, '<span class="sec-link" role="link">' + esc(s.allSessions) + "</span>", '<div class="card list">' + rows + "</div>", "sec-recent") +
      '<span class="btn btn-wide" role="button">' + icon("compose") + esc(s.newChat) + "</span>";
  }

  function screenChatList(p, lang) {
    var s = STRINGS[lang];
    return topBar(s, s.chat, { icons: ["clock", "compose"] }) +
      '<div class="content content-chatlist">' + chatListBody(s) + "</div>" + bottomBar(s, "chat");
  }

  function screenNewChat(p, lang) {
    var s = STRINGS[lang];
    function choice(name, sub, isPath, on) {
      return '<div class="row choice" role="radio" aria-checked="' + on + '"><span class="thumb" aria-hidden="true">' + icon("folder") + "</span>" +
        '<div class="main"><div class="row-t' + (isPath ? " path" : "") + '">' + esc(name) + '</div><div class="row-s"><span>' + esc(sub) + "</span></div></div>" +
        '<span class="radio' + (on ? " on" : "") + '" aria-hidden="true"></span></div>';
    }
    var sheet = '<div class="scrim" aria-hidden="true"></div>' +
      '<section class="sheet" role="dialog" aria-label="' + esc(s.whereRun) + '"><span class="sheet-grip" aria-hidden="true"></span>' +
      '<h3 class="sheet-title">' + esc(s.whereRun) + "</h3>" +
      '<div class="list" role="radiogroup" aria-label="' + esc(s.whereRun) + '">' + choice(s.memoryDefault, s.memorySub, false, true) +
      DATA.allowedFolders.map(function (f) { return choice(f, s.allowedFolder, true, false); }).join("") + "</div>" +
      '<span class="btn btn-wide" role="button">' + esc(s.start) + "</span>" +
      '<span class="gesture" aria-hidden="true"></span></section>';
    return topBar(s, s.chat, { icons: ["clock", "compose"] }) +
      '<div class="content content-chatlist" aria-hidden="true">' + chatListBody(s) + "</div>" + bottomBar(s, "chat") + sheet;
  }

  function screenCoach(p, lang) {
    var s = STRINGS[lang];
    function option(letter, text, on) {
      return '<div class="opt' + (on ? " on" : "") + '" role="radio" aria-checked="' + on + '"><span class="opt-k" aria-hidden="true">' + letter + "</span>" +
        '<span class="opt-t">' + esc(text) + '</span><span class="radio' + (on ? " on" : "") + '" aria-hidden="true"></span></div>';
    }
    var question = '<div class="ask-card question" role="group" aria-label="' + esc(s.question) + '">' +
      '<div class="ask-k"><span class="coach-tag">' + esc(s.coach) + "</span><span>" + esc(s.question) + "</span></div>" +
      '<p class="ask-t">' + esc(s.coachQ) + "</p>" +
      '<div class="opts" role="radiogroup">' + option("A", s.optA, true) + option("B", s.optB, false) + option("C", s.optC, false) + "</div>" +
      '<div class="ask-own"><div class="field"><span class="placeholder">' + esc(s.ownAnswer) + "</span></div>" +
      '<span class="btn" role="button">' + esc(s.send) + "</span></div></div>";
    return topBar(s, s.coach, { back: true, mark: "spark", sub: s.usageToday(DATA.coachRuns), icons: ["clock"] }) +
      '<div class="content content-chat"><div class="thread">' +
      '<div class="sys-line"><span>' + esc(s.summarised) + "</span></div>" +
      '<div class="msg msg-bot"><div class="bubble"><p>' + esc(s.coachM1) + "</p></div></div>" +
      '<div class="msg msg-user"><div class="bubble"><p>' + esc(s.coachU1) + "</p></div></div>" +
      question + "</div></div>" + composer(s) + bottomBar(s, "chat");
  }

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

  function screenSessions(p, lang) {
    var s = STRINGS[lang];
    var data = DATA.sessions;
    function rows(items, folder, pinned) {
      return '<div class="card list">' + items.map(function (ses) { return sessionRow(s, ses, folder, pinned); }).join("") + "</div>";
    }
    var search = '<div class="search" role="search">' + icon("search") + '<span class="placeholder">' + esc(s.searchSessions) + "</span></div>";
    var groups = data.groups.map(function (g) { return section(BASE, s[g.name], esc(s.yourGroup), rows(g.items), "sec-group"); }).join("");
    var folders = data.folders.map(function (f) {
      return '<div class="folder-head">' + icon("folder") + '<span class="path">' + esc(f.folder) + "</span></div>" + rows(f.items, f.folder);
    }).join("");
    return topBar(s, s.sessions, { back: true, icons: ["compose"] }) +
      '<div class="content content-sessions">' + search +
      section(BASE, s.pinned, "", rows(data.pinned, "", true), "sec-pinned") + groups +
      section(BASE, s.byFolder, esc(s.automatic), folders, "sec-folders") +
      "</div>" + bottomBar(s, "more");
  }

  /* Charts (Activity) */

  var CHART = { width: 358, barW: 20, radius: 5, rowH: 26, barH: 6, right: 70 };

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

  function sleepChart(lang, hatchId) {
    var s = STRINGS[lang];
    var cfg = CHART;
    var W = cfg.width, H = 148, left = 30, right = cfg.right, top = 18, plotH = 92;
    var base = top + plotH, maxMin = 540, st = sleepStats();
    function y(min) { return top + plotH * (1 - min / maxMin); }
    var svg = '<svg class="chart chart-sleep" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(sleepSummary(lang)) + '">';
    svg += '<defs><pattern id="' + hatchId + '" patternUnits="userSpaceOnUse" width="6" height="6" patternTransform="rotate(45)">' +
      '<rect width="6" height="6" class="hatch-bg"/><line x1="1.5" y1="0" x2="1.5" y2="6" class="hatch-line"/></pattern></defs>';
    [240, 480].forEach(function (min) {
      svg += '<line class="grid" x1="' + left + '" x2="' + (W - right) + '" y1="' + y(min) + '" y2="' + y(min) + '"/>' +
        '<text class="axis-lbl" x="' + (left - 6) + '" y="' + (y(min) + 3.5) + '" text-anchor="end">' + (min / 60) + " " + s.unitH + "</text>";
    });
    svg += '<line class="baseline" x1="' + left + '" x2="' + (W - right) + '" y1="' + base + '" y2="' + base + '"/>';
    var slot = (W - left - right) / DATA.sleep.length;
    DATA.sleep.forEach(function (n, i) {
      var date = nightDate(i), cx = left + slot * (i + 0.5);
      var x0 = cx - cfg.barW / 2, x1 = cx + cfg.barW / 2, yt = y(n.minutes), r = cfg.radius;
      var d = "M" + x0 + "," + base + "V" + (yt + r) + "Q" + x0 + "," + yt + " " + (x0 + r) + "," + yt +
        "H" + (x1 - r) + "Q" + x1 + "," + yt + " " + x1 + "," + (yt + r) + "V" + base + "Z";
      var label = fmtDate(date, { weekday: "long" }, lang) + ": " + durText(n.minutes, lang) + (n.est ? " (" + s.estimated + ")" : "");
      svg += '<path class="bar' + (n.est ? " bar-est" : "") + '" d="' + d + '"' + (n.est ? ' style="fill:url(#' + hatchId + ')"' : "") + "><title>" + esc(label) + "</title></path>";
      if (n.est) { svg += '<text class="est-lbl" x="' + cx + '" y="' + (yt - 5) + '" text-anchor="middle">' + esc(s.estShort) + "</text>"; }
      svg += '<text class="x-lbl' + (i === DATA.sleep.length - 1 ? " x-last" : "") + '" x="' + cx + '" y="' + (base + 15) +
        '" text-anchor="middle">' + esc(fmtDate(date, { weekday: "short" }, lang)) + "</text>" +
        '<text class="val-lbl" x="' + cx + '" y="' + (base + 30) + '" text-anchor="middle">' + fmtNum(n.minutes / 60, lang, 1) + "</text>";
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

  function appsChart(lang) {
    var s = STRINGS[lang];
    var W = CHART.width, rowH = CHART.rowH, barH = CHART.barH;
    var nameW = 78, valW = 52, trackX = nameW, trackW = W - nameW - valW, max = DATA.appMinutes[0];
    var list = DATA.appMinutes.map(function (m, i) { return s.apps[i] + " " + durText(m, lang); }).join(", ");
    var svg = '<svg class="chart chart-apps" viewBox="0 0 ' + W + " " + (rowH * DATA.appMinutes.length) + '" role="img" aria-label="' + esc(s.appsSummary(list)) + '">';
    DATA.appMinutes.forEach(function (m, i) {
      var cy = rowH * i + rowH / 2, w = Math.round(trackW * m / max);
      svg += '<text class="app-name" x="0" y="' + (cy + 4) + '">' + esc(s.apps[i]) + "</text>" +
        '<rect class="htrack" x="' + trackX + '" y="' + (cy - barH / 2) + '" width="' + trackW + '" height="' + barH + '" rx="' + barH / 2 + '"/>' +
        '<rect class="hbar" x="' + trackX + '" y="' + (cy - barH / 2) + '" width="' + w + '" height="' + barH + '" rx="' + barH / 2 + '"><title>' +
        esc(s.apps[i] + ": " + durText(m, lang)) + "</title></rect>" +
        '<text class="app-val" x="' + W + '" y="' + (cy + 4) + '" text-anchor="end">' + esc(durText(m, lang)) + "</text>";
    });
    return svg + "</svg>";
  }

  function screenActivity(p, lang) {
    var s = STRINGS[lang];
    var hatchId = nextId("hatch");
    var ln = DATA.lastNight;
    var nightRange = fmtDate(addDays(TODAY, -1), { weekday: "short" }, lang) + " → " + fmtDate(TODAY, { weekday: "short", day: "numeric", month: "short" }, lang);
    var tabs = '<div class="tabs" role="tablist"><span class="tab on" role="tab" aria-selected="true">' + esc(s.tabSleep) +
      '</span><span class="tab" role="tab" aria-selected="false">' + esc(s.tabScreen) + "</span></div>";
    var last = section(BASE, s.lastNight, esc(nightRange),
      '<div class="card stats"><div class="stat"><div class="stat-k">' + esc(s.bed) + '</div><div class="stat-v"><span class="num">' + ln.bed + "</span></div></div>" +
      '<div class="stat"><div class="stat-k">' + esc(s.wake) + '</div><div class="stat-v"><span class="num">' + ln.wake + "</span></div></div>" +
      '<div class="stat stat-main"><div class="stat-k">' + esc(s.duration) + '</div><div class="stat-v">' + durHtml(ln.minutes, lang) + "</div></div></div>", "sec-last");
    var range = '<span class="seg" role="group"><span class="seg-i on" aria-pressed="true">' + esc(s.days7) +
      '</span><span class="seg-i" aria-pressed="false">' + esc(s.days30) + "</span></span>";
    var chart = section(BASE, s.sleepChart, range,
      '<div class="card chart-card">' + sleepChart(lang, hatchId) + sleepLegend(lang, hatchId) + '<p class="summary">' + esc(sleepSummary(lang)) + "</p></div>", "sec-chart");
    var screen = section(BASE, s.screenToday, "",
      '<div class="card screen-card"><div class="screen-head"><div class="screen-total">' + durHtml(DATA.screenMinutes, lang) + "</div>" +
      '<div class="screen-meta">' + esc(s.unlocks(DATA.unlocks)) + " · " + esc(s.topApps) + "</div></div>" + appsChart(lang) + "</div>", "sec-screen");
    return topBar(s, s.activity, { back: true }) +
      '<div class="content content-activity">' + tabs + last + chart + screen + "</div>" + bottomBar(s, "more");
  }

  /* ------------------------------------------------------------------ */
  /* Page assembly                                                       */
  /* ------------------------------------------------------------------ */

  var SCREENS = {
    today: screenToday, tasks: screenTasks, courses: screenCourses, assignment: screenAssignment, grades: screenGrades,
    course: screenCourse(0), "course-announcements": screenCourse(1), "course-assignments": screenCourse(2), "course-grades": screenCourse(3),
    chatlist: screenChatList, newchat: screenNewChat, coach: screenCoach, chat: screenChat, sessions: screenSessions, activity: screenActivity
  };
  function screenLabel(s, key) { return s.cap[key] || (key === "grades" ? s.allGrades : s[key]); }

  function phone(p, screen, theme, lang, caption, sub) {
    return '<figure class="frame"><div class="device"><div class="phone screen-' + screen + '" data-prop="' + p + '" data-theme="' + theme + '" lang="' + lang + '">' +
      statusBar() + SCREENS[screen](p, lang) + "</div></div>" +
      "<figcaption><strong>" + esc(caption) + "</strong>" + (sub ? "<span>" + esc(sub) + "</span>" : "") + "</figcaption></figure>";
  }

  function segmented(key, label, options, disabled) {
    var html = '<div class="ctl' + (disabled ? " is-off" : "") + '"><span class="ctl-lbl">' + esc(label) + '</span><span class="ctl-seg" role="group" aria-label="' + esc(label) + '">';
    options.forEach(function (opt) {
      var on = state[key] === opt.value;
      html += '<button type="button" data-key="' + key + '" data-value="' + opt.value + '" aria-pressed="' + on + '"' + (disabled ? " disabled" : "") + ">" + esc(opt.label) + "</button>";
    });
    return html + "</span></div>";
  }

  function propLabel(s, p) { return (p === BASE ? "" : p + " · ") + s.propName[p]; }

  function render() {
    var s = STRINGS[state.lang];
    var single = state.view === "single";
    var themes = state.theme === "both" ? ["light", "dark"] : [state.theme];
    uid = 0;

    document.documentElement.lang = state.lang;
    document.title = s.pageTitle;
    document.body.setAttribute("data-page-theme", themes[0]);
    document.body.setAttribute("data-view", state.view);

    document.getElementById("chrome").innerHTML =
      '<div class="chrome-title"><h1>' + esc(s.pageTitle) + "</h1><p>" + esc(s.pageNote) + "</p></div>" +
      '<div class="ctls">' +
      segmented("view", s.ctlView, [{ value: "compare", label: s.viewCompare }, { value: "single", label: s.viewSingle }]) +
      segmented("prop", s.ctlProp, OPTIONS.prop.map(function (p) { return { value: p, label: propLabel(s, p) }; }), !single) +
      segmented("screen", s.ctlScreen, OPTIONS.screen.map(function (k) { return { value: k, label: screenLabel(s, k) }; }), single) +
      segmented("theme", s.ctlTheme, [{ value: "light", label: s.light }, { value: "dark", label: s.dark }, { value: "both", label: s.both }]) +
      segmented("lang", s.ctlLang, [{ value: "en", label: "EN" }, { value: "tr", label: "TR" }]) +
      "</div>";

    var stage = "";
    if (single) {
      stage += '<p class="blurb"><strong>' + esc(propLabel(s, state.prop)) + "</strong> " + esc(s.propBlurb[state.prop]) + "</p>";
    }
    themes.forEach(function (theme, i) {
      stage += '<section class="band" data-page-theme="' + theme + '">';
      if (themes.length > 1) { stage += '<h2 class="band-title">' + esc(s[theme]) + "</h2>"; }
      if (single) {
        SINGLE_ROWS.forEach(function (row) {
          stage += '<div class="band-row">';
          row.forEach(function (k) {
            var same = state.prop !== BASE && RESTYLED.indexOf(k) === -1;
            stage += phone(state.prop, k, theme, state.lang, screenLabel(s, k), same ? s.asD3 : "");
          });
          stage += "</div>";
        });
      } else {
        PROPOSALS.forEach(function (p) {
          stage += phone(p, state.screen, theme, state.lang, propLabel(s, p), i === 0 ? s.propBlurb[p] : "");
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

  // The Today card scrolls inside itself; at its end the "more" hint goes away.
  document.addEventListener("scroll", function (event) {
    var list = event.target;
    if (!list.classList || !list.classList.contains("scroll-list")) { return; }
    var atEnd = list.scrollTop + list.clientHeight >= list.scrollHeight - 2;
    list.parentNode.parentNode.classList.toggle("at-end", atEnd);
  }, true);

  readUrl();
  render();
}());
