/*
 * LifeOS visual-direction mockups.
 * One content model, one EN/TR string table, four direction skins.
 * D is the chosen skin and comes in three token variants (D1, D2, D3) that
 * share every layout rule. D3 is the approved variant and the default; D1,
 * D2 and the earlier directions A, B and C are kept for reference.
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
      notChosen: "not chosen", chosen: "chosen",
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
      secTop: "Assistant's picks",
      secToday: "Today",
      todayNote: function (n, done) { return n + " tasks · " + done + " done"; },
      openConversation: "Open conversation",
      moreCount: function (n) { return n + " more"; },
      task1: "Draft the Project Alpha outline", task1Why: "Due today; two tasks depend on it", task1Sub: "Today · Project Alpha",
      task2: "Submit Example assignment 1", task2Why: "One day late; about 30 minutes of work", task2Sub: "Yesterday · Example course",
      task3: "Reply about the seminar topic", task3Why: "Waiting for 6 days", task3Sub: "No date · Example seminar",
      task4: "Read chapter 3 of the example book", task4Sub: "Today · Example seminar",
      task5: "Buy printer paper", task5Sub: "Done",
      task6: "Review the check-in notes", task6Sub: "Thursday · Project Alpha",
      task7: "Renew the library book", task7Sub: "Friday",
      task8: "Send the meeting notes", task8Sub: "Today · Project Alpha",
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
      chatQ2: "Add the summary as a task for Monday and clear out the old drafts.",
      coach: "Coach",
      coachQ: "“Reply about the seminar topic” has been waiting for 6 days. What is hard about it?",
      // Chat tab: list state, folder choice, coach conversation, approval card.
      cap: {
        chatlist: "Chat · list", newchat: "New chat · folder", coach: "Coach conversation", chat: "Chat · conversation",
        course: "Course · Materials", "course-announcements": "Course · Announcements",
        "course-assignments": "Course · Assignments", "course-grades": "Course · Grades"
      },
      usageToday: function (n) { return "Today: " + n + " runs"; },
      recent: "Recent", allSessions: "All sessions",
      whereRun: "Where should this chat run?",
      memoryDefault: "Memory (default)", memorySub: "Notes, tasks and daily logs",
      allowedFolder: "Allowed folder", start: "Start", close: "Close",
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
      allGrades: "All grades",
      secCourses: "All courses",
      courseCount: function (n) { return n + " courses"; },
      courseName: function (letter) { return "Example course " + letter; },
      next: "Next:", noDeadline: "No upcoming deadline",
      newCount: function (n) { return n + " new"; },
      item: {
        as1: "Assignment 1", as2: "Assignment 2", quiz1: "Quiz 1", report: "Project report",
        lab1: "Lab report 1", summary: "Reading summary", midterm: "Midterm exam", talk: "Short presentation"
      },
      tabMaterials: "Materials", tabAnnouncements: "Announcements", tabAssignments: "Assignments", tabGrades: "Grades",
      week: function (n, title) { return "Week " + n + " · " + title; },
      weekTitle: { intro: "Introduction", core: "Core concepts" },
      file: { slides1: "Lecture 1 slides", outline: "Course outline", slides2: "Lecture 2 slides", sheet1: "Exercise sheet 1", data: "Example data set" },
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
      notChosen: "seçilmedi", chosen: "seçildi",
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
      secTop: "Asistanın seçtikleri",
      secToday: "Bugün",
      todayNote: function (n, done) { return n + " görev · " + done + " tamam"; },
      openConversation: "Sohbeti aç",
      moreCount: function (n) { return n + " tane daha"; },
      task1: "Alfa Projesi taslağını hazırla", task1Why: "Son gün bugün; iki görev buna bağlı", task1Sub: "Bugün · Alfa Projesi",
      task2: "Örnek ödev 1'i teslim et", task2Why: "Bir gün gecikti; yaklaşık 30 dakikalık iş", task2Sub: "Dün · Örnek ders",
      task3: "Seminer konusu için yanıt yaz", task3Why: "6 gündür bekliyor", task3Sub: "Tarihsiz · Örnek seminer",
      task4: "Örnek kitabın 3. bölümünü oku", task4Sub: "Bugün · Örnek seminer",
      task5: "Yazıcı kâğıdı al", task5Sub: "Tamamlandı",
      task6: "Görüşme notlarını gözden geçir", task6Sub: "Perşembe · Alfa Projesi",
      task7: "Kütüphane kitabının süresini uzat", task7Sub: "Cuma",
      task8: "Toplantı notlarını gönder", task8Sub: "Bugün · Alfa Projesi",
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
      chatQ2: "Özeti pazartesiye görev olarak ekle, eski taslakları da temizle.",
      coach: "Koç",
      coachQ: "“Seminer konusu için yanıt yaz” 6 gündür bekliyor. Seni zorlayan ne?",
      cap: {
        chatlist: "Sohbet · liste", newchat: "Yeni sohbet · klasör", coach: "Koç sohbeti", chat: "Sohbet · konuşma",
        course: "Ders · Materyaller", "course-announcements": "Ders · Duyurular",
        "course-assignments": "Ders · Ödevler", "course-grades": "Ders · Notlar"
      },
      usageToday: function (n) { return "Bugün: " + n + " çalıştırma"; },
      recent: "Son sohbetler", allSessions: "Tüm oturumlar",
      whereRun: "Bu sohbet nerede çalışsın?",
      memoryDefault: "Hafıza (varsayılan)", memorySub: "Notlar, görevler ve günlükler",
      allowedFolder: "İzin verilen klasör", start: "Başlat", close: "Kapat",
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
      allGrades: "Tüm notlar",
      secCourses: "Tüm dersler",
      courseCount: function (n) { return n + " ders"; },
      courseName: function (letter) { return "Örnek ders " + letter; },
      next: "Sıradaki:", noDeadline: "Yaklaşan teslim yok",
      newCount: function (n) { return n + " yeni"; },
      item: {
        as1: "Ödev 1", as2: "Ödev 2", quiz1: "Kısa sınav 1", report: "Proje raporu",
        lab1: "Deney raporu 1", summary: "Okuma özeti", midterm: "Ara sınav", talk: "Kısa sunum"
      },
      tabMaterials: "Materyaller", tabAnnouncements: "Duyurular", tabAssignments: "Ödevler", tabGrades: "Notlar",
      week: function (n, title) { return n + ". hafta · " + title; },
      weekTitle: { intro: "Giriş", core: "Temel kavramlar" },
      file: { slides1: "1. ders slaytları", outline: "Ders izlencesi", slides2: "2. ders slaytları", sheet1: "Alıştırma kâğıdı 1", data: "Örnek veri seti" },
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
      // Drawn in D only, where the card scrolls; A, B and C keep the short list.
      { title: "task8", sub: "task8Sub", extra: true },
      { title: "task5", sub: "task5Sub", done: true }
    ],
    todayVisible: 3, // rows the Today card shows before it scrolls
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
    // Chat tab: the pinned coach conversation, recent conversations, folder choice.
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
      { letter: "A", next: "as2", inDays: 2, fresh: 3, total: 86 },
      { letter: "B", next: "report", inDays: 9, total: 74 },
      { letter: "C", next: "lab1", inDays: 5, fresh: 1, total: 90 },
      { letter: "D" },
      { letter: "E", next: "quiz1", inDays: 16 }
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
    courseGrades: [
      { item: "as1", grade: 85, hi: 100 },
      { item: "talk", grade: 18, hi: 20 },
      { item: "summary", hi: 20 },
      { item: "as2", hi: 100 },
      { item: "midterm", hi: 100 }
    ],
    allGrades: [
      { letter: "A", total: 86, rows: [{ item: "as1", grade: 85, hi: 100 }, { item: "talk", grade: 18, hi: 20 }] },
      { letter: "B", total: 74, rows: [{ item: "quiz1", grade: 7, hi: 10 }, { item: "as1", grade: 72, hi: 100 }] },
      { letter: "C", total: 90, rows: [{ item: "lab1", grade: 9, hi: 10 }] },
      { letter: "D", total: 92, rows: [{ item: "as1", grade: 92, hi: 100 }] },
      { letter: "E", rows: [] }
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
    view: ["single", "compare", "directions"],
    dir: ["D1", "D2", "D3", "A", "B", "C"],
    screen: ["today", "chatlist", "newchat", "coach", "chat", "tasks", "sessions", "activity", "courses", "course", "grades"],
    theme: ["light", "dark", "both"],
    lang: ["en", "tr"]
  };
  // D1, D2 and D3 are token variants of one skin ("D"): same markup, same
  // layout rules, different custom-property values.
  var VARIANTS = ["D1", "D2", "D3"];
  function skinOf(dir) { return VARIANTS.indexOf(dir) === -1 ? dir : "D"; }
  // Screens drawn per skin, one array per row of the single view. Everything
  // except Today, the conversation and Activity exists only in the chosen
  // skin. The course page is drawn once per tab; only its first tab
  // ("course") can be picked in the comparison views.
  var SKIN_SCREENS = {
    A: [["today", "chat", "activity"]],
    B: [["today", "chat", "activity"]],
    C: [["today", "chat", "activity"]],
    D: [
      ["today", "chatlist", "newchat", "coach", "chat"],
      ["tasks", "sessions", "activity", "courses", "grades"],
      ["course", "course-announcements", "course-assignments", "course-grades"]
    ]
  };
  function hasScreen(dir, screen) {
    return SKIN_SCREENS[skinOf(dir)].some(function (row) { return row.indexOf(screen) !== -1; });
  }
  var CHOSEN = "D3"; // the approved variant and the default
  // A bare "D" means the chosen variant; hyphenated screen names are accepted.
  var ALIASES = { dir: { d: CHOSEN }, screen: { "chat-list": "chatlist", chats: "chatlist", "new-chat": "newchat" } };
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
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3"/><path d="M18 3v4.4h-4.4"/>',
    download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14"/>',
    chevron: '<path d="M9 5l7 7-7 7"/>',
    down: '<path d="M5 9l7 7 7-7"/>',
    file: '<path d="M7 3.5h6.5L18 8v11a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z"/><path d="M13.5 3.5V8H18"/>',
    link: '<path d="M10.5 13.5a4 4 0 0 0 5.7 0l2.8-2.8a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M13.5 10.5a4 4 0 0 0-5.7 0L5 13.3A4 4 0 0 0 10.7 19l1-1"/>',
    play: '<circle cx="12" cy="12" r="8.5"/><path d="M10.2 8.4l5.4 3.6-5.4 3.6z"/>',
    out: '<path d="M10 5.5H6.5A1.5 1.5 0 0 0 5 7v10.5A1.5 1.5 0 0 0 6.5 19H17a1.5 1.5 0 0 0 1.5-1.5V14"/><path d="M14 4.5h5.5V10M19.5 4.5l-8 8"/>',
    spark: '<path d="M12 4l2.1 5.9L20 12l-5.9 2.1L12 20l-2.1-5.9L4 12l5.9-2.1z"/>',
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
    if (opts.mark) { html += '<span class="thumb is-featured tb-mark" aria-hidden="true">' + icon(opts.mark) + "</span>"; }
    html += '<div class="tb-titles"><h2 class="tb-title">' + (dir === "C" ? '<span class="prompt" aria-hidden="true">&gt;</span>' : "") +
      esc(title) + "</h2>" + (opts.sub ? '<span class="tb-sub">' + esc(opts.sub) + "</span>" : "") + "</div>";
    html += '<span class="tb-spacer"></span>';
    if (opts.note) { html += '<span class="tb-note">' + esc(opts.note) + "</span>"; }
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
        var label = { bell: s.reminders, clock: s.sessions, compose: s.newChat, search: s.search, refresh: s.refresh }[name];
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

  /** A section drawn as one visible card, heading included (direction D). */
  function panel(label, note, body, extraClass) {
    return '<section class="panel ' + (extraClass || "") + '"><div class="sec-head"><h3>' + esc(label) + "</h3>" +
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

    // The featured "coach card". In D it carries the coach label and the accent
    // ring, and it opens the coach conversation.
    var advice = '<section class="card advice"' + (dir === "D" ? ' role="link"' : "") + '><div class="advice-k">' +
      (dir === "D" ? '<span class="coach-tag">' + esc(s.coach) + "</span>" : "") +
      "<span>" + esc(s.adviceLabel) + "</span>" +
      (dir === "D" ? '<span class="advice-open">' + esc(s.openConversation) + icon("chevron") + "</span>" : "") + "</div>" +
      '<p class="advice-t">' + esc(s.adviceText) + "</p>" +
      '<p class="advice-why"><b>' + esc(s.why) + "</b> " + esc(s.adviceWhy) + "</p></section>";

    var top = DATA.top.map(function (t, i) {
      return '<div class="row pick"><span class="rank num" aria-hidden="true">' + (i + 1) + "</span>" +
        '<div class="main"><div class="row-t">' + esc(s[t.title]) + '</div><div class="row-s"><span>' + esc(s[t.why]) + "</span></div></div></div>";
    }).join("");

    var list = DATA.today.filter(function (t) { return dir === "D" || !t.extra; });
    var tasks = list.map(function (t) { return taskRow(s, t); }).join("");
    var note = esc(s.todayNote(list.length, list.filter(function (t) { return t.done; }).length));
    var body;

    if (dir === "D") {
      // Three cards. The Today card shows three tasks and scrolls inside
      // itself; a fade and a count say that there is more below.
      var more = list.length - DATA.todayVisible;
      body = advice +
        panel(s.secTop, "", '<div class="list">' + top + "</div>", "sec-top") +
        panel(s.secToday, note,
          '<div class="scroll-box"><div class="scroll-list" tabindex="0" role="group" aria-label="' + esc(s.secToday) + '">' + tasks + "</div>" +
          '<span class="scroll-fade" aria-hidden="true"></span></div>' +
          '<div class="scroll-more">' + esc(s.moreCount(more)) + icon("down") + "</div>", "sec-today");
    } else {
      body = advice +
        section(s.secTop, "", '<div class="card list">' + top + "</div>", "sec-top") +
        section(s.secToday, note, '<div class="card list">' + tasks + "</div>", "sec-today");
    }

    return topBar(dir, s, s.today, { icons: ["bell"], bellCount: DATA.newReminders }) +
      '<div class="content content-today">' + body +
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

  function composer(dir, s) {
    return '<div class="composer"><div class="field">' +
      (dir === "C" ? '<span class="prompt" aria-hidden="true">&gt;</span>' : "") +
      '<span class="placeholder">' + esc(s.composer) + "</span></div>" +
      '<span class="send" role="button" aria-label="' + esc(s.send) + '">' +
      (dir === "C" ? '<span class="send-lbl">' + esc(s.send) + "</span>" : icon(dir === "B" ? "plane" : "up")) + "</span></div>";
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
    // In D the run is paused by an approval card: what will happen and where,
    // two answers, and the fixed note about changes that were already made.
    var ap = DATA.approval;
    var approval = '<div class="ask-card approval" role="group" aria-label="' + esc(s.approvalLabel) + '">' +
      '<div class="ask-k">' + esc(s.approvalLabel) + "</div>" +
      '<p class="ask-t">' + esc(s.approvalText[0]) + '<span class="path">' + esc(ap.path) + "</span>" + esc(s.approvalText[1]) + "</p>" +
      '<p class="ask-files path">' + ap.files.map(esc).join(" · ") + "</p>" +
      '<div class="ask-acts"><span class="btn" role="button">' + esc(s.allow) + "</span>" +
      '<span class="btn btn-quiet" role="button">' + esc(s.deny) + "</span></div>" +
      '<p class="ask-note">' + esc(s.keptNote) + "</p></div>";

    return topBar(dir, s, s.chat, { back: dir === "D", newChat: true, icons: ["clock", "compose"] }) +
      '<div class="content content-chat"><div class="thread">' + userMsg(s.chatQ1) + answer + userMsg(s.chatQ2) +
      (dir === "D" ? approval : working) + "</div></div>" +
      composer(dir, s) + floatingFab(dir, s) + bottomBar(dir, s, "chat");
  }

  /* ------------------------------------------------------------------ */
  /* Screens: Chat tab list, folder choice, coach conversation (D only)  */
  /* ------------------------------------------------------------------ */

  function chatListBody(s) {
    // The coach conversation is pinned first: ring mark, last message, usage.
    var coach = '<section class="panel coach-pin" role="link"><div class="row session">' +
      '<span class="thumb is-featured" aria-hidden="true">' + icon("spark") + "</span>" +
      '<div class="main"><div class="row-t">' + esc(s.coach) + '</div><div class="row-s"><span class="clip">' + esc(s.coachQ) + "</span></div>" +
      '<div class="row-s row-usage"><span>' + esc(s.usageToday(DATA.coachRuns)) + "</span></div></div>" +
      '<span class="row-side"><span class="row-when num">08:30</span>' +
      '<span class="row-icon row-pin" role="img" aria-label="' + esc(s.pinned) + '" title="' + esc(s.pinned) + '">' + icon("pin") + "</span></span></div></section>";
    var rows = DATA.recent.map(function (ses) { return sessionRow(s, ses, "", false); }).join("");
    return coach +
      section(s.recent, '<span class="sec-link" role="link">' + esc(s.allSessions) + "</span>", '<div class="card list">' + rows + "</div>", "sec-recent") +
      '<span class="btn btn-wide" role="button">' + icon("compose") + esc(s.newChat) + "</span>";
  }

  function screenChatList(dir, lang) {
    var s = STRINGS[lang];
    return topBar(dir, s, s.chat, { icons: ["clock", "compose"] }) +
      '<div class="content content-chatlist">' + chatListBody(s) + "</div>" +
      bottomBar(dir, s, "chat");
  }

  /** Starting a new chat: a sheet over the list asks where it should run. */
  function screenNewChat(dir, lang) {
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
    return topBar(dir, s, s.chat, { icons: ["clock", "compose"] }) +
      '<div class="content content-chatlist" aria-hidden="true">' + chatListBody(s) + "</div>" +
      bottomBar(dir, s, "chat") + sheet;
  }

  /** The coach conversation: usage in the header, a question card in the run. */
  function screenCoach(dir, lang) {
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
    return topBar(dir, s, s.coach, { back: true, mark: "spark", sub: s.usageToday(DATA.coachRuns), icons: ["clock"] }) +
      '<div class="content content-chat"><div class="thread">' +
      '<div class="sys-line"><span>' + esc(s.summarised) + "</span></div>" +
      '<div class="msg msg-bot"><div class="bubble"><p>' + esc(s.coachM1) + "</p></div></div>" +
      '<div class="msg msg-user"><div class="bubble"><p>' + esc(s.coachU1) + "</p></div></div>" +
      question + "</div></div>" +
      composer(dir, s) + bottomBar(dir, s, "chat");
  }

  /* ------------------------------------------------------------------ */
  /* Screens: Courses, course page, all grades (D only)                  */
  /* ------------------------------------------------------------------ */

  function shortDate(offset, lang) { return fmtDate(addDays(TODAY, offset), { day: "numeric", month: "short" }, lang); }
  function longDate(offset, lang) { return fmtDate(addDays(TODAY, offset), { day: "numeric", month: "long" }, lang); }
  function fileSize(mb, lang) { return mb < 1 ? Math.round(mb * 1000) + " KB" : fmtNum(mb, lang, 1) + " MB"; }

  function screenCourses(dir, lang) {
    var s = STRINGS[lang];
    var soon = DATA.courses.filter(function (c) { return c.next; })
      .sort(function (a, b) { return a.inDays - b.inDays; }).slice(0, 3);
    var deadlines = soon.map(function (c) {
      return '<div class="row plain"><div class="main"><div class="row-t">' + esc(s.item[c.next]) + '</div><div class="row-s"><span>' +
        esc(s.courseName(c.letter)) + '</span></div></div><span class="due num">' + esc(s.dueIn(c.inDays)) + "</span></div>";
    }).join("");
    var summary = panel(s.secDeadlines, esc(s.deadlinesNote),
      '<div class="list">' + deadlines + "</div>" +
      '<span class="btn btn-wide" role="button">' + esc(s.allGrades) + "</span>", "sec-deadlines");

    var rows = DATA.courses.map(function (c) {
      var sub = c.next ? s.next + " " + s.item[c.next] + " · " + s.dueIn(c.inDays) : s.noDeadline;
      return '<div class="row course" role="link"><span class="thumb" aria-hidden="true">' + c.letter + "</span>" +
        '<div class="main"><div class="row-t">' + esc(s.courseName(c.letter)) + '</div><div class="row-s"><span>' + esc(sub) + "</span></div></div>" +
        (c.fresh ? '<span class="badge num" role="img" aria-label="' + esc(s.newCount(c.fresh)) + '" title="' + esc(s.newCount(c.fresh)) + '">' + c.fresh + "</span>" : "") +
        '<span class="row-icon" aria-hidden="true">' + icon("chevron") + "</span></div>";
    }).join("");

    return topBar(dir, s, s.courses, { back: true, note: s.lastSync, icons: ["refresh"] }) +
      '<div class="content content-courses">' + summary +
      section(s.secCourses, esc(s.courseCount(DATA.courses.length)), '<div class="card list">' + rows + "</div>", "sec-courses") +
      "</div>" + bottomBar(dir, s, "more");
  }

  function gradeRow(s, g) {
    var graded = typeof g.grade === "number";
    return '<div class="row plain grade"><div class="main"><div class="row-t">' + esc(s.item[g.item]) + '</div><div class="row-s"><span>' +
      esc(graded ? s.range(0, g.hi) : s.notGraded) + '</span></div></div><span class="grade-v num' + (graded ? "" : " is-empty") + '">' +
      (graded ? g.grade : "–") + "</span></div>";
  }

  var COURSE_TABS = ["tabMaterials", "tabAnnouncements", "tabAssignments", "tabGrades"];

  function courseTab(s, lang, tab) {
    if (tab === 0) {
      var sections = DATA.materials.map(function (m) {
        var files = m.files.map(function (f) {
          var meta = f.type + " · " + fileSize(f.mb, lang) + " · " + shortDate(f.day, lang);
          return '<div class="row file"><span class="thumb" aria-hidden="true">' + icon("file") + "</span>" +
            '<div class="main"><div class="row-t"><span class="clip">' + esc(s.file[f.name]) + "</span>" +
            (f.fresh ? '<span class="new-tag">' + esc(s.newTag) + "</span>" : "") + "</div>" +
            '<div class="row-s">' + (f.saved ? '<span class="saved">' + icon("check") + esc(s.saved) + "</span>" : "<span>" + esc(meta) + "</span>") + "</div></div>" +
            '<span class="icon-btn' + (f.saved ? " is-done" : "") + '" role="button" aria-label="' + esc((f.saved ? s.downloaded : s.download) + ": " + s.file[f.name]) + '" title="' +
            esc(f.saved ? s.downloaded : s.download) + '">' + icon(f.saved ? "check" : "download") + "</span></div>";
        }).join("");
        return section(s.week(m.week, s.weekTitle[m.title]), "", '<div class="card list">' + files + "</div>", "sec-files");
      }).join("");
      var links = DATA.links.map(function (l) {
        var video = l.kind === "video";
        return '<div class="row file link-row" role="link"><span class="thumb" aria-hidden="true">' + icon(video ? "play" : "link") + "</span>" +
          '<div class="main"><div class="row-t">' + esc(s.link[l.name]) + '</div><div class="row-s"><span>' +
          esc(video ? s.video + " · " + durText(l.minutes, lang) : s.externalLink) + "</span></div></div>" +
          '<span class="row-icon" role="img" aria-label="' + esc(s.open) + '">' + icon("out") + "</span></div>";
      }).join("");
      return sections + section(s.links, "", '<div class="card list">' + links + "</div>", "sec-files sec-links");
    }
    if (tab === 1) {
      return DATA.announcements.map(function (a) {
        var body = s.ann[a.key + "Body"](typeof a.dueDay === "number" ? longDate(a.dueDay, lang) : "");
        return '<article class="panel ann"><div class="ann-head"><h3 class="row-t">' + esc(s.ann[a.key]) + "</h3>" +
          (a.fresh ? '<span class="new-tag">' + esc(s.newTag) + "</span>" : "") +
          '<span class="ann-date num">' + esc(shortDate(a.day, lang)) + "</span></div>" +
          '<p class="ann-body">' + esc(body) + "</p></article>";
      }).join("");
    }
    if (tab === 2) {
      var rows = DATA.assignments.map(function (a) {
        var when = s.due(shortDate(a.day, lang)) + (a.day > 0 ? " · " + s.dueIn(a.day) : "");
        return '<div class="row plain assignment"><div class="main"><div class="row-t">' + esc(s.item[a.item]) + '</div><div class="row-s"><span>' + esc(when) + "</span></div></div>" +
          '<span class="chip chip-' + a.status + '"><span class="chip-mark" aria-hidden="true">' + (a.status === "submitted" || a.status === "graded" ? icon("check") : "") + "</span>" +
          esc(s.st[a.status]) + (a.status === "graded" ? ' <span class="num">' + a.grade + "/" + a.hi + "</span>" : "") + "</span></div>";
      }).join("");
      return '<section class="panel"><div class="list">' + rows + "</div></section>";
    }
    var total = DATA.courses[0].total;
    return '<section class="panel total"><div class="row plain grade"><div class="main"><div class="row-t">' + esc(s.courseTotal) + '</div><div class="row-s"><span>' +
      esc(s.range(0, 100)) + '</span></div></div><span class="grade-v grade-total num">' + total + "</span></div></section>" +
      '<section class="panel"><div class="list">' + DATA.courseGrades.map(function (g) { return gradeRow(s, g); }).join("") + "</div></section>";
  }

  function screenCourse(tab) {
    return function (dir, lang) {
      var s = STRINGS[lang];
      var tabs = '<div class="tabs tabs-4" role="tablist">' + COURSE_TABS.map(function (key, i) {
        return '<span class="tab' + (i === tab ? " on" : "") + '" role="tab" aria-selected="' + (i === tab) + '">' + esc(s[key]) + "</span>";
      }).join("") + "</div>";
      return topBar(dir, s, s.courseName("A"), { back: true, icons: ["refresh"] }) +
        '<div class="content content-course">' + tabs + courseTab(s, lang, tab) + "</div>" +
        bottomBar(dir, s, "more");
    };
  }

  function screenGrades(dir, lang) {
    var s = STRINGS[lang];
    var groups = DATA.allGrades.map(function (c) {
      var body = c.rows.length ? c.rows.map(function (g) { return gradeRow(s, g); }).join("") :
        '<p class="empty-line">' + esc(s.noGrades) + "</p>";
      return panel(s.courseName(c.letter), typeof c.total === "number" ? esc(s.total) + ' <b class="num">' + c.total + "</b>" : "",
        '<div class="list">' + body + "</div>", "sec-grades");
    }).join("");
    return topBar(dir, s, s.allGrades, { back: true }) +
      '<div class="content content-grades">' + groups + "</div>" + bottomBar(dir, s, "more");
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

  var SCREENS = {
    today: screenToday, chatlist: screenChatList, newchat: screenNewChat, coach: screenCoach, chat: screenChat,
    tasks: screenTasks, sessions: screenSessions, activity: screenActivity,
    courses: screenCourses, grades: screenGrades,
    course: screenCourse(0), "course-announcements": screenCourse(1),
    "course-assignments": screenCourse(2), "course-grades": screenCourse(3)
  };
  // Name of a screen in the switcher and under a phone.
  function screenLabel(s, key) { return s.cap[key] || (key === "grades" ? s.allGrades : s[key]); }

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
    return d + " · " + s.dirName[d] + " (" + (d === CHOSEN ? s.chosen : s.notChosen) + ")";
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
      segmented("screen", s.ctlScreen, OPTIONS.screen.map(function (k) { return { value: k, label: screenLabel(s, k) }; }), single) +
      segmented("theme", s.ctlTheme, [{ value: "light", label: s.light }, { value: "dark", label: s.dark }, { value: "both", label: s.both }]) +
      segmented("lang", s.ctlLang, [{ value: "en", label: "EN" }, { value: "tr", label: "TR" }]) +
      "</div>";

    // compare: the three variants of the chosen skin. directions: the chosen
    // variant next to the earlier directions that have this screen.
    var dirs = state.view === "compare" ? VARIANTS : [CHOSEN, "A", "B", "C"].filter(function (d) {
      return hasScreen(d, state.screen);
    });

    var stage = "";
    if (single) {
      stage += '<p class="blurb"><strong>' + esc(dirLabel(s, state.dir)) + "</strong> " + esc(s.dirBlurb[state.dir]) + "</p>";
    }
    themes.forEach(function (theme, i) {
      stage += '<section class="band" data-page-theme="' + theme + '">';
      if (themes.length > 1) { stage += '<h2 class="band-title">' + esc(s[theme]) + "</h2>"; }
      if (single) {
        SKIN_SCREENS[skinOf(state.dir)].forEach(function (row) {
          stage += '<div class="band-row">';
          row.forEach(function (k) { stage += phone(state.dir, k, theme, state.lang, screenLabel(s, k), ""); });
          stage += "</div>";
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
