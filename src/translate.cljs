(ns translate
  "Optional Spanish translation through Google's website translator. Nothing
  from Google loads until a visitor switches to ES, which counts as consent.
  The widget remembers the choice in its `googtrans` cookie, so a returning
  visitor who left the page in Spanish gets it translated again. A link with
  `?lang=es` translates right away and `?lang=en` forces English. While the
  page is in Spanish its URL has `?lang=es`, so it can be shared as is.")

(def ^:private script-url
  "https://translate.google.com/translate_a/element.js?cb=durhamTranslateInit")

(def ^:private wanted "en")
(def ^:private loader nil)
(def ^:private note-timer nil)

(defn- spanish-cookie? [] (.test #"(^|;\s*)googtrans=/en/es" js/document.cookie))

(defn- translated? [] (.contains (.-classList js/document.documentElement) "translated-ltr"))

(defn- keep-translating
  "Pokes a hidden node's text now and then. Text that changes while the race
  animates every frame otherwise waits for the next DOM text change before
  Google translates it."
  []
  (let [node (js/document.createElement "span")]
    (set! (.-hidden node) true)
    (.append js/document.body node)
    (js/setInterval #(when (translated?) (set! (.-textContent node) (if (identical? (.-textContent node) "") "." ""))) 700)))

(defn- wait-for-combo
  "Polls for the widget's hidden language dropdown, which appears shortly
  after the widget initializes."
  [resolve reject]
  (letfn [(check [tries]
            (if-let [combo (js/document.querySelector ".goog-te-combo")]
              (resolve combo)
              (if (> tries 80)
                (reject (js/Error. "Translator didn't start"))
                (js/setTimeout #(check (inc tries)) 100))))]
    (check 0)))

(defn- load-widget
  "Loads Google's translator once and resolves to its language dropdown."
  []
  (or loader
      (set! loader
            (js/Promise.
             (fn [resolve reject]
               (aset js/window "durhamTranslateInit"
                     (fn []
                       (js/google.translate.TranslateElement.
                        {:pageLanguage "en" :includedLanguages "es" :autoDisplay false}
                        "google-translate")
                       (keep-translating)
                       (wait-for-combo resolve reject)))
               (js/document.head.append
                (doto (js/document.createElement "script")
                  (set! -src script-url)
                  (set! -onerror #(reject (js/Error. "Translator failed to load"))))))))))

(defn- translate-page
  "Picks Spanish in the widget's `combo` until the page reports it's
  translated. The dropdown shows up before the widget is ready, so early
  picks can be silently ignored."
  [combo]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(pick [tries]
               (cond (translated?) (resolve)
                     (not (= wanted "es")) (resolve)
                     (> tries 20) (reject (js/Error. "Translator didn't respond"))
                     :else (do (when (zero? (mod tries 3))
                                 (set! (.-value combo) "es")
                                 (.dispatchEvent combo (js/Event. "change")))
                               (js/setTimeout #(pick (inc tries)) 250))))]
       (pick 0)))))

(defn- show-lang-in-url!
  "Adds `?lang=es` to the address bar for Spanish and removes `lang` for
  English, keeping other params and the hash. It replaces the history entry,
  so toggling doesn't pile up Back button steps."
  [lang]
  (let [url (js/URL. js/location.href)]
    (if (= lang "es")
      (.set (.-searchParams url) "lang" "es")
      (.delete (.-searchParams url) "lang"))
    ;; Some browsers refuse URL changes on file:// pages
    (try (js/history.replaceState js/history.state "" url) (catch :default _ nil))))

(defn- forget-spanish! []
  (doseq [domain ["" (str "; domain=" js/location.hostname) (str "; domain=." js/location.hostname)]]
    (set! js/document.cookie (str "googtrans=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/" domain))))

(defn- restore-english
  "Clicks the widget's own Show original button, which lives in its hidden
  banner. Falls back to clearing the cookie and reloading."
  []
  (let [banner (js/document.querySelector "iframe.skiptranslate")
        button (some-> banner .-contentDocument (.querySelector "[id$='.restore']"))]
    (if button
      (.click button)
      (do (forget-spanish!) (js/location.reload)))))

(defn init
  "Wires the EN/ES `toggle` button and the `note` element that reports
  progress and failures."
  [toggle note]
  (let [render (fn [] (.setAttribute toggle "aria-checked" (str (= wanted "es"))))
        say (fn [text ms]
              (js/clearTimeout note-timer)
              (set! (.-textContent note) text)
              (when ms (set! note-timer (js/setTimeout #(set! (.-textContent note) "") ms))))
        switch-to (fn [lang]
                    (set! wanted lang)
                    (render)
                    (show-lang-in-url! lang)
                    (if (= lang "es")
                      (do (.add (.-classList toggle) "busy")
                          (say "Traduciendo…" nil)
                          (-> (load-widget)
                              (.then translate-page)
                              (.then #(say "" nil))
                              (.catch (fn []
                                        (set! loader nil)
                                        (set! wanted "en")
                                        (render)
                                        (show-lang-in-url! "en")
                                        (say "Translation unavailable right now" 4000)))
                              (.finally #(.remove (.-classList toggle) "busy"))))
                      (do (say "" nil)
                          (when (translated?) (restore-english)))))]
    (.addEventListener toggle "click" #(switch-to (if (= wanted "es") "en" "es")))
    (let [lang (.get (js/URLSearchParams. js/location.search) "lang")]
      (cond (= lang "es") (switch-to "es")
            (= lang "en") (forget-spanish!)
            (spanish-cookie?) (switch-to "es")))))
