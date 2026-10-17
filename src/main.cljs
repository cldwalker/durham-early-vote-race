(ns main
  (:require ["canvas-confetti$default" :as confetti]
            ["../early-vote-counts.csv$default" :as counts-csv]
            ["../voter-phrases.csv$default" :as phrases-csv]
            [curve :refer [monotone-curve]]
            [data :refer [parse-counts parse-phrases]]
            [runner :refer [looks viewbox create-runner run-pose celebrate-pose]]))

(def race-ms 10000)
(def unit "ballots")
(def countdown ["3" "2" "1" "Go!"])
(def beat-ms 650)
;; Fraction of the runner's width where the chest is; this is what crosses the lines
(def anchor 0.65)
(def aspect (/ (:w viewbox) (:h viewbox)))
(def default-years ["2022" "2026"])
(def start-lead (str "Press start to race or <a href=\"#more-about-early-voting\">learn more</a> "
                     "about early voting."))
;; Debug/screenshot aid: index.html?at=6.5 loads with the race already 6.5s in
(def skip-to (.get (js/URLSearchParams. js/location.search) "at"))

(defn- $ [id] (js/document.getElementById id))
(def number-format (js/Intl.NumberFormat. "en-US"))
(defn- fmt [n] (.format number-format (js/Math.round n)))
(defn- clamp [v lo hi] (min hi (max lo v)))
(defn- set-text! [id text] (set! (.-textContent ($ id)) text))
(defn- el [tag class text]
  (let [node (js/document.createElement tag)]
    (set! (.-className node) class)
    (set! (.-textContent node) text)
    node))
(defn- restart-animation!
  "Re-triggers the CSS animation tied to `class` on `node`."
  [node class]
  (.remove (.-classList node) class)
  (.-offsetWidth node)
  (.add (.-classList node) class))

(def counts (parse-counts counts-csv))
(def years (:years counts))
(def days (:days counts))
(def phrases (parse-phrases phrases-csv))

(def scene ($ "scene"))
(def road ($ "road"))
(def overlay ($ "overlay"))
(def countdown-el ($ "countdown"))
(def result ($ "result"))
(def cheer ($ "cheer"))
(def cheer-msg ($ "cheer-msg"))
(def clip ($ "clip"))
(def clip-url "https://www.youtube-nocookie.com/embed/ukmibZfZTb8?start=241&rel=0")
(def reduced-motion (js/matchMedia "(prefers-reduced-motion: reduce)"))

(set-text! "subtitle" "Pick two years of early voting and see which year had more ballots.")

;; Indexes into `years` of the year running in each lane
(def picked
  (let [found (.map default-years (fn [y] (.findIndex years #(identical? (:name %) y))))]
    (if (or (.includes found -1) (== (first found) (last found)))
      [(max 0 (- (count years) 2)) (dec (count years))]
      found)))

(def runners [])
(def best 1)
(def decided true)
;; Race time at which an in-progress year runs out of data, and that runner
(def pause-ms nil)
(def chaser nil)
;; Track geometry in px, recomputed on resize
(def G {})
(def mode :idle) ; :idle | :countdown | :racing | :paused | :finished
(def count-start 0)
(def race-start 0)
(def paused-at 0)
(def paused-once false)
(def finished-at 0)
(def shown-beat -1)
(def final-lead "")
(def last-frame (js/performance.now))
(def phrase-index -1)

(declare build-race)

(defn- pick-year
  "Puts year `i` in `lane`. Picking the year that's in the other lane swaps the two lanes."
  [lane i]
  (when-not (== (aget picked lane) i)
    (let [other (- 1 lane)]
      (when (== (aget picked other) i) (aset picked other (aget picked lane)))
      (aset picked lane i)
      (build-race))))

(def pickers
  (.map [0 1]
        (fn [lane]
          (let [group (el "div" "pick" "")
                chips (.map years
                            (fn [{:keys [name complete]} i]
                              (let [chip (el "button" "chip" name)]
                                (set! (.-type chip) "button")
                                (when-not complete (.append chip (el "small" "" "in progress")))
                                (.addEventListener chip "click" #(pick-year lane i))
                                (.append group chip)
                                chip)))]
            (.setProperty (.-style group) "--c" (:shirt (aget looks lane)))
            (.setAttribute group "role" "group")
            (.setAttribute group "aria-label" (str "Lane " (inc lane) " year"))
            {:group group :chips chips}))))
(.append ($ "matchup") (:group (aget pickers 0)) (el "span" "vs" "vs") (:group (aget pickers 1)))

(defn- render-pickers []
  (.forEach pickers
    (fn [{:keys [chips]} lane]
      (.forEach chips
        (fn [chip i]
          (let [theirs (== i (aget picked (- 1 lane)))]
            (.setAttribute chip "aria-pressed" (str (== i (aget picked lane))))
            (.toggle (.-classList chip) "taken" theirs)
            (set! (.-title chip) (if theirs "Swap lanes" ""))))))))

(defn- make-runner [{:keys [name complete data-days vote-days cumulative]} slot]
  (let [look (aget looks (mod slot (count looks)))
        fig (create-runner look name)
        lane (el "div" "lane" "")
        card (el "div" "card" "")]
    (.setProperty (.-style lane) "--c" (:shirt look))
    (set! (.-innerHTML lane) (str #html [:<> [:div.bubble [:b name] [:span "0"]] [:div.runner]]))
    (.append (.querySelector lane ".runner") (:el fig))
    (.append road lane)
    (.setProperty (.-style card) "--c" (:shirt look))
    (set! (.-innerHTML card)
          (str #html [:<>
                      [:div.card-top [:span.swatch] [:span.card-name name] [:span.card-day]]
                      [:div.card-total [:span.num "0"] " " [:span.card-unit unit]]
                      [:div.bar [:i]]]))
    (.append ($ "cards") card)
    {:name name :look look :fig fig :card card :complete complete
     :curve (monotone-curve cumulative {:end-at-rest (not complete)})
     :end-k data-days
     :vote-days vote-days
     :card-day (.querySelector card ".card-day")
     :total (last cumulative)
     :winner false
     :wrap (.querySelector lane ".runner")
     :bubble (.querySelector lane ".bubble")
     :bubble-total (.querySelector lane ".bubble span")
     :card-total (.querySelector card ".num")
     :bar (.querySelector card ".bar i")
     :phase (* slot 1.7)
     :x 0 :v 0 :cum 0 :glide 0 :celebrate-at nil :shown ""}))

(defn- layout []
  (let [W (.-clientWidth road)
        rh (js/Math.round (clamp (* W 0.11) 62 120))
        rw (* rh aspect)
        start (max (* W 0.07) (+ (* rw anchor) 8))
        finish (- W (max (* W 0.12) (* rw 1.5)))]
    (set! G {:W W :rw rw :start start :finish finish :span (- finish start)})
    (.setProperty (.-style scene) "--runner-h" (str rh "px"))
    (.setProperty (.-style scene) "--runner-w" (str rw "px"))
    (doseq [id ["start-line" "start-sign"]] (set! (.. ($ id) -style -left) (str start "px")))
    (doseq [id ["finish-line" "finish-sign"]] (set! (.. ($ id) -style -left) (str finish "px")))))

(defn- turn-off-tv []
  (set! (.-hidden clip) true)
  (.remove (.-classList clip) "on")
  (.replaceChildren ($ "tv-screen")))

(defn- turn-on-tv
  "Loads the clip into the TV without autoplaying it, so YouTube isn't
  contacted until someone asks for it."
  []
  (.add (.-classList clip) "on")
  (.replaceChildren ($ "tv-screen")
                    (doto (js/document.createElement "iframe")
                      (.setAttribute "src" clip-url)
                      (.setAttribute "title" "Bonus clip")
                      (.setAttribute "allow" "encrypted-media; picture-in-picture; fullscreen")
                      (.setAttribute "allowfullscreen" "")
                      (.setAttribute "referrerpolicy" "strict-origin-when-cross-origin"))))

(defn- reset []
  (doseq [r runners]
    (assoc! r :v 0 :cum 0 :glide 0 :celebrate-at nil :winner (== (:total r) best))
    (.remove (.-classList (:bubble r)) "won"))
  ;; A race against an in-progress year is only decided if that year is
  ;; already strictly ahead
  (let [winners (.filter runners #(:winner %))]
    (set! decided (.every runners #(or (:complete %) (and (:winner %) (= 1 (count winners)))))))
  (set! paused-once false)
  (.remove (.-classList ($ "finish-sign")) "won")
  (set! (.-hidden result) true)
  (turn-off-tv))

(defn- close-cheer []
  (when (and (.-open cheer) (not (.contains (.-classList cheer) "closing")))
    (if (.-matches reduced-motion)
      (.close cheer)
      (.add (.-classList cheer) "closing"))))

(defn- build-race []
  (close-cheer)
  (doseq [lane (.querySelectorAll road ".lane")] (.remove lane))
  (.replaceChildren ($ "cards"))
  (set! runners (.map picked (fn [yi slot] (make-runner (aget years yi) slot))))
  (set! best (.reduce runners #(max %1 (:total %2)) 1))
  (let [ends (.sort (.filter runners #(not (:complete %))) #(- (:end-k %1) (:end-k %2)))]
    (set! chaser (first ends))
    (set! pause-ms (when chaser (* (/ (:end-k chaser) days) race-ms)))
    (set-text! "note" (.join (.concat [(str "Each runner moves at the pace of their year's daily " unit ". ")]
                                      (.map ends #(str (:name %) " is still in progress, with data through day "
                                                       (:end-k %) ".")))
                             " ")))
  (render-pickers)
  (set! mode :idle)
  (reset)
  (set! (.-hidden overlay) false)
  (set! (.-hidden countdown-el) true)
  (set! (.-innerHTML ($ "lead")) start-lead))

(defn- start-countdown []
  (reset)
  (set! (.-hidden overlay) true)
  (set! mode :countdown)
  (set! count-start (js/performance.now))
  (set! shown-beat -1))

(defn- show-beat [text]
  (set! (.-hidden countdown-el) false)
  (set! (.-textContent countdown-el) text)
  (restart-animation! countdown-el "pop"))

(defn- celebrate []
  (let [rect (.getBoundingClientRect scene)
        origin {:x (/ (+ (.-left rect) (:finish G)) js/innerWidth)
                :y (/ (+ (.-top rect) (.-offsetTop road) (/ (.-offsetHeight road) 2)) js/innerHeight)}
        colors (.concat (.map (.filter runners #(:winner %)) #(:shirt (:look %))) ["#facc15" "#ffffff" "#22c55e"])
        burst #(confetti (js/Object.assign {:colors colors :disableForReducedMotion true :zIndex 50} %))]
    (burst {:particleCount 140 :spread 80 :startVelocity 40 :origin origin})
    (js/setTimeout #(do (burst {:particleCount 80 :angle 60 :spread 60 :origin {:x 0 :y 0.9}})
                        (burst {:particleCount 80 :angle 120 :spread 60 :origin {:x 1 :y 0.9}}))
                   350)
    (js/setTimeout #(burst {:particleCount 100 :spread 120 :origin origin}) 900)))

(defn- show-result! [title sub lead]
  (set-text! "result-title" title)
  (set-text! "result-sub" sub)
  (set! final-lead lead))

(defn- finish [now]
  (set! mode :finished)
  (set! finished-at now)
  (set! (.-hidden result) false)
  (set! (.-hidden clip) false)
  (let [ranked (.sort (.slice runners) #(- (:total %2) (:total %1)))
        w (aget ranked 0) runner-up (aget ranked 1)
        winners (.filter ranked #(:winner %))]
    (cond
      (not decided)
      (let [leader (.find ranked #(not (identical? % chaser)))
            needed (fmt (inc (- (:total leader) (:total chaser))))]
        (show-result! (str "🏁 " (:name leader) " finished with " (fmt (:total leader)) " " unit)
                      (str (:name chaser) " has " (fmt (:total chaser)) " through day " (:end-k chaser)
                           " and needs " needed " more to win. It's up to us!")
                      (str (:name chaser) " needs " needed " more " unit " to win")))

      (> (count winners) 1)
      (show-result! "🤝 It's a tie!"
                    (str (.join (.map winners #(:name %)) " and ") " both finished with " (fmt best) " " unit ".")
                    "It's a tie!")

      :else
      (show-result! (str "🏆 " (:name w) " wins!")
                    (if runner-up
                      (str (fmt (:total w)) " to " (fmt (:total runner-up)) ", a margin of "
                           (fmt (- (:total w) (:total runner-up))) " " unit ".")
                      (str (fmt (:total w)) " " unit "."))
                    (str (:name w) " wins!")))
    (when decided
      (.add (.-classList ($ "finish-sign")) "won")
      (celebrate))))

(defn- update-runner [r k elapsed dt now]
  (let [{:keys [start span finish]} G]
    (if (< elapsed race-ms)
      ;; An in-progress year stops where its data ends
      (let [{:keys [value slope]} ((:curve r) (min k (:end-k r)))]
        (assoc! r :cum value
                :x (+ start (* (/ value best) span))
                :v (if (< k (:end-k r)) (* (/ (* slope days) (/ race-ms 1000) best) span) 0)))
      ;; Coast to a stop after the clock runs out
      (let [glide (+ (:glide r) (* (:v r) dt))
            x (+ start (* (/ (:total r) best) span) glide)]
        (assoc! r :cum (:total r) :glide glide :v (* (:v r) (js/Math.exp (* -2 dt)))
                :x (if (:winner r) x (min x (- finish 4))))))
    (let [norm (/ (:v r) (/ span (/ race-ms 1000)))]
      (if (and (:winner r) decided (= mode :finished) (or (< norm 0.15) (> (- now finished-at) 700)))
        (do (when (nil? (:celebrate-at r))
              (assoc! r :celebrate-at now)
              (.add (.-classList (:bubble r)) "won"))
            ((:set-pose (:fig r)) (celebrate-pose (/ (- now (:celebrate-at r)) 1000))))
        (do (assoc! r :phase (+ (:phase r) (* 2 js/Math.PI (+ 0.9 (* 1.5 (min norm 1.6))) dt)))
            ((:set-pose (:fig r)) (run-pose (:phase r) (clamp (* norm 1.5) 0 1))))))))

(defn- render-runner [{:keys [x cum bubble] :as r}]
  (set! (.. (:wrap r) -style -transform) (str "translateX(" (.toFixed (- x (* (:rw G) anchor)) 1) "px)"))
  (let [text (fmt cum)]
    (when-not (identical? text (:shown r))
      (assoc! r :shown text)
      (set! (.-textContent (:bubble-total r)) text)
      (set! (.-textContent (:card-total r)) text)))
  (set! (.. (:bar r) -style -width) (str (* (/ cum best) 100) "%"))
  ;; Keep the label above the runner's head but inside the track
  (let [bw (.-offsetWidth bubble)
        left (clamp (- x (/ bw 2)) 4 (- (:W G) bw 4))]
    (set! (.. bubble -style -transform) (str "translateX(" (.toFixed left 1) "px)"))
    (.setProperty (.-style bubble) "--tip" (str (.toFixed (clamp (- x left) 10 (- bw 10)) 1) "px"))))

(defn- day-label
  "Labels how far into its own early voting period `r` is, since years can
  have different numbers of early voting days."
  [{:keys [vote-days complete]} k]
  (case mode
    (:idle :countdown) (str vote-days (if (== vote-days 1) " day" " days") (when-not complete " so far"))
    (str "Day " (if (= mode :finished) vote-days (clamp (js/Math.ceil k) 1 vote-days))
         (if complete (str " of " vote-days) " so far"))))

(defn- render-board [k]
  (doseq [r runners] (set! (.-textContent (:card-day r)) (day-label r k)))
  (let [ranked (.sort (.slice runners) #(- (:cum %2) (:cum %1)))
        first-r (aget ranked 0) second-r (aget ranked 1)]
    (doseq [r runners]
      (.toggle (.-classList (:card r)) "leading" (and (not= mode :idle) (identical? r first-r) (pos? (:cum r)))))
    (when (not= mode :idle)
      (let [gap (if second-r (- (:cum first-r) (:cum second-r)) 0)]
        (set-text! "lead" (cond (= mode :countdown) "On your marks…"
                                (= mode :finished) final-lead
                                (zero? (js/Math.round gap)) "Neck and neck!"
                                :else (str (:name first-r) " leads by " (fmt gap) " " unit)))))))

(defn- show-phrase []
  (when (pos? (count phrases))
    (let [n (count phrases)
          i (js/Math.floor (* (js/Math.random) n))
          ;; Never repeat the phrase that's showing
          i (if (and (> n 1) (== i phrase-index))
              (mod (+ i 1 (js/Math.floor (* (js/Math.random) (dec n)))) n)
              i)]
      (set! phrase-index i)
      (set! (.-textContent cheer-msg) (aget phrases i))
      (restart-animation! cheer-msg "swap"))))

(defn- open-cheer []
  (let [other (.find runners #(not (identical? % chaser)))]
    (set-text! "cheer-title" (str "Will " (:name chaser) " beat out " (:name other) "? It's up to us!"))
    (set! phrase-index -1)
    (show-phrase)
    (.remove (.-classList cheer) "closing")
    (.showModal cheer)
    (.focus ($ "cheer-next"))))

(defn- pause [now]
  (set! mode :paused)
  (set! paused-once true)
  (set! paused-at now)
  (set-text! "lead" (str (:name chaser) " is waiting on you…"))
  (open-cheer))

(defn- resume []
  (when (= mode :paused)
    (let [now (js/performance.now)]
      (set! race-start (+ race-start (- now paused-at)))
      (set! last-frame now)
      (set! mode :racing))))

(defn- step [now dt]
  (when (not= mode :paused)
    (when (= mode :countdown)
      (let [beat (js/Math.floor (/ (- now count-start) beat-ms))]
        (when (and (not (== beat shown-beat)) (< beat (count countdown)))
          (set! shown-beat beat)
          (show-beat (aget countdown beat)))
        (when (>= beat (dec (count countdown)))
          (set! mode :racing)
          (set! race-start (+ count-start (* (dec (count countdown)) beat-ms))))))
    (let [elapsed (if (or (= mode :racing) (= mode :finished)) (- now race-start) 0)
          ;; Freeze on the exact moment an in-progress year runs out of data
          pause-now (and (= mode :racing) pause-ms (not paused-once) (>= elapsed pause-ms))
          elapsed (if pause-now pause-ms elapsed)
          k (* (/ (min elapsed race-ms) race-ms) days)]
      (when pause-now (set! race-start (- now pause-ms)))
      (when (and (= mode :racing) (not pause-now) (>= elapsed race-ms)) (finish now))
      (doseq [r runners]
        (update-runner r k elapsed dt now)
        (render-runner r))
      (render-board k)
      (when pause-now (pause now)))))

(defn- frame [now]
  (step now (min 0.05 (/ (- now last-frame) 1000)))
  (set! last-frame now)
  (js/requestAnimationFrame frame))

(defn- skip-ahead [seconds]
  (set! (.-hidden overlay) true)
  (set! mode :racing)
  (set! race-start (- last-frame (* seconds 1000)))
  (loop [t race-start]
    (when (< t last-frame)
      (step t (/ 1 60))
      (recur (+ t (/ 1000 60))))))

(.addEventListener cheer "animationend"
                   (fn [e]
                     (when (and (identical? (.-target e) cheer) (.contains (.-classList cheer) "closing"))
                       (.remove (.-classList cheer) "closing")
                       (.close cheer))))
(.addEventListener cheer "close" resume)
(.addEventListener cheer "cancel" (fn [e] (.preventDefault e) (close-cheer)))
;; Clicks outside the card land on the dialog element itself (its backdrop)
(.addEventListener cheer "click" #(when (identical? (.-target %) cheer) (close-cheer)))
(.addEventListener ($ "cheer-close") "click" close-cheer)
(.addEventListener ($ "cheer-done") "click" close-cheer)
(.addEventListener ($ "cheer-next") "click" show-phrase)
(.addEventListener ($ "start-btn") "click" start-countdown)
(.addEventListener ($ "tv-power") "click" turn-on-tv)
(.addEventListener ($ "again-btn") "click"
                   #(do (start-countdown) (.scrollIntoView scene {:behavior "smooth" :block "center"})))
(.observe (js/ResizeObserver. layout) road)
(layout)
(build-race)
(when (some? skip-to) (skip-ahead (if (js/isNaN skip-to) 0 (js/Number skip-to))))
(js/requestAnimationFrame frame)
