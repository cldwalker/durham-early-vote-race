(ns main
  (:require ["canvas-confetti$default" :as confetti]
            ["../early-vote-counts.csv$default" :as counts-csv]
            ["../voter-phrases.csv$default" :as phrases-csv]
            ["../positive-voting-actions.csv$default" :as actions-csv]
            [curve :refer [monotone-curve]]
            [data :refer [parse-counts parse-phrases]]
            [runner :refer [looks viewbox create-runner run-pose celebrate-pose]]
            [translate]))

(def race-ms 5000)
(def unit "ballots")
(def countdown ["3" "2" "1" "Go!"])
(def beat-ms 450)
;; Fraction of the runner's width where the chest is; this is what crosses the lines
(def anchor 0.65)
(def aspect (/ (:w viewbox) (:h viewbox)))
(def default-years ["2022" "2026"])
;; Shown in the lead line until a race starts, in both views
(def idle-lead "<a href=\"#early-voting-links\">Learn more about early voting</a>")
;; Debug/screenshot aid: index.html?at=6.5 loads with the race already 6.5s in
(def skip-to (.get (js/URLSearchParams. js/location.search) "at"))
;; "race" or "data", which shows the picked years' ballots as bar charts
(def view (if (identical? (.get (js/URLSearchParams. js/location.search) "view") "data") "data" "race"))
;; "daily" ballots or their running "total" in the data view
(def chart "daily")

(defn- $ [id] (js/document.getElementById id))
(def number-format (js/Intl.NumberFormat. "en-US"))
(defn- fmt [n] (.format number-format (js/Math.round n)))
(defn- clamp [v lo hi] (min hi (max lo v)))
(defn- put!
  "Sets `node`'s text, or its HTML when `html?`, only when it changed. Rewriting
  unchanged text every frame would make a translated page revert and re-translate."
  [node text html?]
  (when-not (identical? (aget node "_shown") text)
    (aset node "_shown" text)
    (if html? (set! (.-innerHTML node) text) (set! (.-textContent node) text))))
(defn- set-text! [id text] (put! ($ id) text false))
(defn- put-num!
  "Shows `text` through a `data-n` attribute that CSS displays. Changing many
  numbers' text at once, or every frame, would stop Google Translate from
  translating the other text that changed alongside them."
  [node text]
  (.setAttribute node "data-n" text))
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
;; Shown behind the player as they run. The page keeps them as invisible text
;; so Google Translate has them in Spanish before they're needed
(def actions (doto (el "ul" "sr-only" "")
               (.setAttribute "aria-hidden" "true")
               (set! -innerHTML (.join (.map (parse-phrases actions-csv) #(str #html [:li %])) ""))))
(.append js/document.body actions)
(def action-index 0)

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

(set-text! "subtitle" "Pick two years of early voting and see which year wins the ballot race!")

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
;; The runner a visitor moves by tapping the track or pressing ← or → in
;; "Race them yourself", or nil. `playing` is whether Race again includes them
(def player nil)
(def playing false)
;; How quickly the player's speed fades, per second, and the taps per second
;; it takes to keep pace with the race clock
(def player-drag 1)
(def target-taps 2.5)
;; Whether the cheer modal is closing through its Keep racing button
(def keep-racing false)

(declare build-race)

(defn- paint!
  "Sets `look`'s colors on `node`: its shirt as `--c` and, for text that has
  to stay readable on white or under white, a darker `--c-text`"
  [node look]
  (.setProperty (.-style node) "--c" (:shirt look))
  (.setProperty (.-style node) "--c-text" (:text look)))

(defn- pick-year
  "Puts year `i` in the first lane so the second lane's pinned year, at first
  the one in progress, stays put. Picking a year that's already racing swaps
  lanes, which pins the first lane's year."
  [i]
  (if (.includes picked i) (.reverse picked) (aset picked 0 i))
  (build-race))

(def picker-chips
  (let [group (el "div" "pick" "")]
    ;; Hovering a year previews it in the first lane's color, where it'd go
    (paint! group (aget looks 0))
    (.setAttribute group "role" "group")
    (.setAttribute group "aria-label" "Years racing")
    (.before (.querySelector ($ "matchup") ".pick-hint") group)
    (.map years
          (fn [{:keys [name complete]} i]
            (let [chip (el "button" "chip" name)]
              (set! (.-type chip) "button")
              (when-not complete (.append chip (el "small" "" "in progress")))
              (.addEventListener chip "click" #(pick-year i))
              (.append group chip)
              chip)))))

(defn- render-pickers []
  (.forEach picker-chips
    (fn [chip i]
      (let [lane (.indexOf picked i)
            style (.-style chip)]
        (.setAttribute chip "aria-pressed" (str (not (neg? lane))))
        (if (neg? lane)
          (do (.removeProperty style "--c") (.removeProperty style "--c-text"))
          (paint! chip (aget looks lane)))
        (.toggle (.-classList chip) "pinned" (== lane 1))
        (set! (.-title chip) (case lane 0 "Pin this year" 1 "Pinned" "")))))
  (put-num! ($ "hint-pinned") (:name (aget years (aget picked 1))))
  (put-num! ($ "hint-other") (:name (aget years (aget picked 0)))))

(defn- make-lane
  "Adds a lane to the track with a runner wearing `look` and `bib`, labelled
  `name` in its bubble."
  [name look bib]
  (let [fig (create-runner look bib)
        lane (el "div" "lane" "")]
    (paint! lane look)
    (set! (.-innerHTML lane) (str #html [:<> [:div.bubble.notranslate [:b name] [:span.n {:data-n "0"}]] [:div.runner]]))
    (.append (.querySelector lane ".runner") (:el fig))
    (.append road lane)
    {:lane lane :fig fig :look look
     :wrap (.querySelector lane ".runner")
     :bubble (.querySelector lane ".bubble")
     :bubble-total (.querySelector lane ".bubble span")}))

(defn- make-runner [{:keys [name complete data-days vote-days cumulative]} slot]
  (let [look (aget looks slot)
        card (el "div" "card" "")]
    (paint! card look)
    (set! (.-innerHTML card)
          (str #html [:<>
                      [:div.card-top [:span.swatch] [:span.card-name name] [:span.card-day]]
                      [:div.card-total [:span.num.n.notranslate {:data-n "0"}] [:span.card-unit unit]]
                      [:div.bar [:i]]]))
    (.append ($ "cards") card)
    (js/Object.assign
     (make-lane name look name)
     {:name name :card card :complete complete
     :curve (monotone-curve cumulative {:end-at-rest (not complete)})
     :end-k data-days
     :vote-days vote-days
     :card-day (.querySelector card ".card-day")
     :total (last cumulative)
     :cumulative cumulative
     :winner false
     :card-total (.querySelector card ".num")
     :bar (.querySelector card ".bar i")
     :phase (* slot 1.7)
     :x 0 :v 0 :cum 0 :glide 0 :celebrate-at nil :shown ""})))

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
  (set! (.-hidden ($ "result-you")) true)
  (turn-off-tv))

(defn- close-cheer []
  (when (and (.-open cheer) (not (.contains (.-classList cheer) "closing")))
    (if (.-matches reduced-motion)
      (.close cheer)
      (.add (.-classList cheer) "closing"))))

(declare day-label)

(defn- day-value
  "Returns `r`'s ballots on `day`, or their running total through it, or a
  label for a day without ballots."
  [{:keys [cumulative end-k vote-days]} day]
  (cond (> day end-k) "not yet"
        (> day vote-days) "no early voting"
        :else (if (identical? chart "total")
                (aget cumulative day)
                (- (aget cumulative day) (aget cumulative (dec day))))))

(defn- update-data
  "Sizes and labels the data view's bars for the current `chart`."
  []
  (set-text! "data-title" (if (identical? chart "total") "Running total of ballots" "Ballots per day"))
  (doseq [btn (.querySelectorAll ($ "chart-switch") "button")]
    (.setAttribute btn "aria-pressed" (str (identical? (.. btn -dataset -chart) chart))))
  (let [bars (js/Array.from (.querySelectorAll ($ "days") ".day-bar"))
        values (.map bars #(day-value (aget runners (.. % -dataset -lane)) (js/Number (.. % -dataset -day))))
        most (.reduce values #(if (number? %2) (max %1 %2) %1) 1)]
    (.forEach bars
              (fn [bar i]
                (let [v (aget values i)]
                  (.setProperty (.-style bar) "--p" (if (number? v) (/ v most) 0))
                  (.toggle (.-classList bar) "empty" (not (number? v)))
                  ;; Numbers stay out of translation but labels like "not yet" don't
                  (put-num! (.querySelector bar ".day-val") (if (number? v) (fmt v) ""))
                  (put! (.querySelector bar ".day-note") (if (number? v) "" v) false))))))

(defn- show-data
  "Fills the data view and the scoreboard with the picked years' full totals."
  []
  (doseq [r runners]
    (put-num! (:card-total r) (fmt (:total r)))
    (set! (.. (:bar r) -style -width) (str (* (/ (:total r) best) 100) "%"))
    (put! (:card-day r) (day-label r 0) false))
  (set! (.-innerHTML ($ "data-legend"))
        (.join (.map runners #(str #html [:span {:style (str "--c:" (:shirt (:look %)))}
                                          (:name %) (when-not (:complete %) " (in progress)")]))
               ""))
  (set! (.-innerHTML ($ "days"))
        ;; Through the last day either picked year has ballots for, which skips
        ;; days that only a third, longer year had
        (.join (.map (js/Array.from {:length (.reduce runners #(max %1 (if (:complete %2) (:vote-days %2) (:end-k %2))) 0)})
                     (fn [_ i]
                       (str #html [:li.day
                                   [:span.day-label "Day " (inc i)]
                                   [:div.day-bars
                                    (.map runners
                                          (fn [r lane]
                                            #html [:div.day-bar {:style (str "--c:" (:shirt (:look r)))
                                                                 :data-lane lane :data-day (inc i)}
                                                   [:span.sr-only (:name r) ": "]
                                                   [:span.day-fill]
                                                   [:span.day-val.n.notranslate]
                                                   [:span.day-note]]))]])))
               ""))
  ;; Lay the bars out empty first so they grow into place
  (.-offsetWidth ($ "days"))
  (update-data))

(defn- set-url-param!
  "Sets `k` to `v` in the address bar, or removes it when `v` is nil."
  [k v]
  (let [url (js/URL. js/location.href)]
    (if v (.set (.-searchParams url) k v) (.delete (.-searchParams url) k))
    ;; Some browsers refuse URL changes on file:// pages
    (try (js/history.replaceState js/history.state "" url) (catch :default _ nil))))


(defn- show-view
  "Switches between the race and the data view, starting either one fresh."
  [v]
  (set! view v)
  (doseq [btn (.querySelectorAll ($ "view-switch") "button")]
    (.setAttribute btn "aria-pressed" (str (identical? (.. btn -dataset -view) v))))
  (set! (.-hidden scene) (identical? v "data"))
  (set! (.-hidden ($ "data")) (identical? v "race"))
  (build-race))

(defn- build-race []
  (close-cheer)
  (doseq [lane (.querySelectorAll road ".lane")] (.remove lane))
  (set! player nil)
  (set! (.-hidden ($ "pad")) true)
  (.replaceChildren ($ "cards"))
  (set! runners (.map picked (fn [yi slot] (make-runner (aget years yi) slot))))
  (set! best (.reduce runners #(max %1 (:total %2)) 1))
  (let [ends (.sort (.filter runners #(not (:complete %))) #(- (:end-k %1) (:end-k %2)))]
    (set! chaser (first ends))
    (set! pause-ms (when chaser (* (/ (:end-k chaser) days) race-ms)))
    (set-text! "note" (.join (.concat (if (identical? view "race")
                                        [(str "Each runner moves at the pace of their year's daily " unit ".")]
                                        [])
                                      (.map ends #(str (:name %) " is still in progress, with data through day "
                                                       (:end-k %) ".")))
                             " ")))
  (render-pickers)
  (set! mode :idle)
  (reset)
  (set! (.-hidden overlay) false)
  (set! (.-hidden countdown-el) true)
  (put! ($ "lead") idle-lead true)
  (when (identical? view "data") (show-data)))

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

(declare show-player-result)

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
    ;; The year that crosses first celebrates even when an in-progress year
    ;; could still catch it, which only changes the result text
    (.add (.-classList ($ "finish-sign")) "won")
    (celebrate)
    (when player (show-player-result))))

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
      (if (and (:winner r) (= mode :finished) (or (< norm 0.15) (> (- now finished-at) 700)))
        (do (when (nil? (:celebrate-at r))
              (assoc! r :celebrate-at now)
              (.add (.-classList (:bubble r)) "won"))
            ((:set-pose (:fig r)) (celebrate-pose (/ (- now (:celebrate-at r)) 1000))))
        (do (assoc! r :phase (+ (:phase r) (* 2 js/Math.PI (+ 0.9 (* 1.5 (min norm 1.6))) dt)))
            ((:set-pose (:fig r)) (run-pose (:phase r) (clamp (* norm 1.5) 0 1))))))))

(defn- place-runner
  "Moves `r`'s figure to its `:x` and keeps its bubble above its head but
  inside the track."
  [{:keys [x wrap bubble]}]
  (set! (.. wrap -style -transform) (str "translateX(" (.toFixed (- x (* (:rw G) anchor)) 1) "px)"))
  (let [bw (.-offsetWidth bubble)
        left (clamp (- x (/ bw 2)) 4 (- (:W G) bw 4))]
    (set! (.. bubble -style -transform) (str "translateX(" (.toFixed left 1) "px)"))
    (.setProperty (.-style bubble) "--tip" (str (.toFixed (clamp (- x left) 10 (- bw 10)) 1) "px"))))

(defn- render-runner [{:keys [cum] :as r}]
  (place-runner r)
  (let [text (fmt cum)]
    (when-not (identical? text (:shown r))
      (assoc! r :shown text)
      (put-num! (:bubble-total r) text)
      (put-num! (:card-total r) text)))
  (set! (.. (:bar r) -style -width) (str (* (/ cum best) 100) "%")))

(defn- secs [ms] (.toFixed (/ ms 1000) 1))

(defn- show-player-result
  "Says how the player did against the year that crossed the line first."
  []
  (let [{:keys [done-at frac]} player
        leader (:name (.reduce runners #(if (> (:total %2) (:total %1)) %2 %1)))]
    (set-text! "result-you"
               (cond (nil? done-at) (str "🕹️ Keep going! You're " (js/Math.floor (* 100 frac)) "% of the way there.")
                     (<= done-at race-ms) (str "🕹️ You crossed the line in " (secs done-at) "s, ahead of every year!")
                     :else (str "🕹️ You crossed the line in " (secs done-at) "s, " (secs (- done-at race-ms))
                                "s after " leader ".")))
    (set! (.-hidden ($ "result-you")) false)))

(defn- update-player
  "Moves the player by their speed, which fades unless they keep tapping, and
  celebrates if they cross the finish line ahead of every year. Their bubble shows how far
  they've run in ballots, on the same scale as the years. Crossing the line
  shows one ballot more than the most any year had when they got there first,
  or one less than the winning year's total when they didn't."
  [p dt now]
  (let [{:keys [start span]} G
        T (/ race-ms 1000)]
    (when (or (= mode :racing) (= mode :finished))
      (let [v (* (:v p) (js/Math.exp (* (- player-drag) dt)))
            frac (min 1 (+ (:frac p) (* v dt)))]
        (assoc! p :v (if (< frac 1) v 0) :frac frac)
        (when (and (>= frac 1) (nil? (:done-at p)))
          (assoc! p :done-at (- now race-start))
          ;; Only a player who beat every year jumps for joy
          (when (<= (:done-at p) race-ms)
            (assoc! p :celebrate-at now)
            (.add (.-classList (:bubble p)) "won"))
          (when (= mode :finished) (show-player-result)))))
    (assoc! p :x (+ start (* (:frac p) span)))
    (let [{:keys [done-at frac]} p
          ;; The year with the most ballots, `best`, crosses the line first
          winner-total best
          text (fmt (cond (nil? done-at) (* frac best)
                          (<= done-at race-ms) (inc best)
                          :else (dec winner-total)))]
      (when-not (identical? text (:shown p))
        (assoc! p :shown text)
        (put-num! (:bubble-total p) text)))
    (if (:celebrate-at p)
      ((:set-pose (:fig p)) (celebrate-pose (/ (- now (:celebrate-at p)) 1000)))
      (let [norm (* (:v p) T)]
        (assoc! p :phase (+ (:phase p) (* 2 js/Math.PI (+ 0.9 (* 1.5 (min norm 1.6))) dt)))
        ((:set-pose (:fig p)) (run-pose (:phase p) (clamp (* norm 1.5) 0 1)))))
    (place-runner p)))

(defn- leave-action
  "Leaves the next positive voting action behind the player, at most every
  450ms and three at a time, so fast tapping doesn't pile them up. It stays
  inside the track, even when the player is near the start."
  [now]
  (let [items (.-children actions)]
    (when (and (pos? (.-length items)) (>= (- now (:said-at player)) 450))
      (assoc! player :said-at now)
      (let [lane (:lane player)
            msg (el "span" "tap-msg notranslate" (.-textContent (aget items (mod action-index (.-length items)))))
            shown (.querySelectorAll lane ".tap-msg")
            {:keys [W rw]} G]
        (set! action-index (inc action-index))
        (when (>= (.-length shown) 3) (.remove (aget shown 0)))
        (.addEventListener msg "animationend" #(.remove msg))
        (.append lane msg)
        (let [w (.-offsetWidth msg)]
          ;; 16px of room on the left for the message drifting back as it fades
          (set! (.. msg -style -left) (str (clamp (- (:x player) (* rw anchor) w -12) 16 (- W w 4)) "px")))))))

(defn- step-player
  "Speeds the player up by one tap while they're racing."
  []
  (when (and player (or (= mode :racing) (= mode :finished)) (nil? (:done-at player)))
    (assoc! player :v (+ (:v player) (/ player-drag (* (/ race-ms 1000) target-taps))))
    (leave-action (js/performance.now))))

(defn- start-race
  "Starts the countdown, with a lane for the player when `play?`."
  [play?]
  (set! playing play?)
  (when player (.remove (:lane player)))
  (set! player (when play?
                 (js/Object.assign (make-lane "You" (aget looks 2) "YOU")
                                   {:x 0 :v 0 :frac 0 :phase 0 :shown "" :said-at 0
                                    :done-at nil :celebrate-at nil})))
  ;; Each race starts somewhere new in the list of actions
  (set! action-index (js/Math.floor (* (js/Math.random) 1000)))
  (set! (.-hidden ($ "pad")) (not play?))
  (start-countdown)
  ;; The cheer modal would interrupt the player, so their races skip it
  (set! paused-once play?))

(defn- day-label
  "Labels how far into its own early voting period `r` is, since years can
  have different numbers of early voting days."
  [{:keys [vote-days complete]} k]
  (case mode
    (:idle :countdown) (str vote-days (if (== vote-days 1) " day" " days") (when-not complete " so far"))
    (str "Day " (if (= mode :finished) vote-days (clamp (js/Math.ceil k) 1 vote-days))
         (if complete (str " of " vote-days) " so far"))))

(defn- render-board [k]
  (doseq [r runners] (put! (:card-day r) (day-label r k) false))
  (let [ranked (.sort (.slice runners) #(- (:cum %2) (:cum %1)))
        first-r (aget ranked 0) second-r (aget ranked 1)]
    (doseq [r runners]
      (.toggle (.-classList (:card r)) "leading" (and (not= mode :idle) (identical? r first-r) (pos? (:cum r)))))
    (when (not= mode :idle)
      (let [gap (if second-r (- (:cum first-r) (:cum second-r)) 0)]
        (if (or (= mode :countdown) (= mode :finished) (zero? (js/Math.round gap)))
          (set-text! "lead" (cond (= mode :countdown) (if player "Tap the track or press → to run!" "On your marks…")
                                  (= mode :finished) final-lead
                                  :else "Neck and neck!"))
          ;; Only the notranslate gap changes each frame, so a translated
          ;; sentence stays translated
          (let [lead ($ "lead")]
            (put! lead (str #html [:<> (:name first-r) " leads by" [:span.gap.n.notranslate] unit]) true)
            (put-num! (.querySelector lead ".gap") (fmt gap))))))))

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
    (set! (.-open ($ "cheer-say")) false)
    (.remove (.-classList cheer) "closing")
    (.showModal cheer)
    (.focus ($ "cheer-next"))))

(defn- pause [now]
  (set! mode :paused)
  (set! paused-once true)
  (set! paused-at now)
  (set-text! "lead" (str (:name chaser) " is waiting on you…"))
  (open-cheer))

(defn- cheer-closed
  "Continues the race when the cheer modal closed through Keep racing.
  Dismissing it any other way leaves the runners where they stopped, with
  the start button back over the track."
  []
  (when (= mode :paused)
    (if keep-racing
      (let [now (js/performance.now)]
        (set! race-start (+ race-start (- now paused-at)))
        (set! last-frame now)
        (set! mode :racing))
      (set! (.-hidden overlay) false)))
  (set! keep-racing false))

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
      (when player (update-player player dt now))
      (render-board k)
      (when pause-now (pause now)))))

(defn- frame [now]
  (when (identical? view "race") (step now (min 0.05 (/ (- now last-frame) 1000))))
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
(.addEventListener cheer "close" cheer-closed)
(.addEventListener cheer "cancel" (fn [e] (.preventDefault e) (close-cheer)))
;; Clicks outside the card land on the dialog element itself (its backdrop)
(.addEventListener cheer "click" #(when (identical? (.-target %) cheer) (close-cheer)))
(.addEventListener ($ "cheer-close") "click" close-cheer)
(.addEventListener ($ "cheer-done") "click" #(do (set! keep-racing true) (close-cheer)))
(.addEventListener ($ "cheer-next") "click" show-phrase)
(.addEventListener ($ "start-btn") "click" #(start-race false))
(.addEventListener ($ "play-btn") "click" #(start-race true))
(.addEventListener js/document "keydown"
                   (fn [e]
                     ;; → or Space runs. ← also works for anyone who tries it. A
                     ;; held key's auto-repeat doesn't count as taps
                     (when (and player (or (= mode :racing) (= mode :finished)) (nil? (:done-at player))
                                (not (.-repeat e))
                                (.includes ["ArrowRight" "ArrowLeft" " "] (.-key e))
                                ;; Space still presses a focused button like Race again
                                (not (and (identical? (.-key e) " ") (.closest (.-target e) "button"))))
                       ;; Keep the keys from scrolling the page or pressing a
                       ;; focused button mid-race
                       (.preventDefault e)
                       (step-player))))
;; Any tap on the track counts, including the Run button
(.addEventListener scene "pointerdown"
                   (fn [e]
                     (when (and player (.-hidden overlay))
                       (.preventDefault e)
                       (step-player))))
(.addEventListener ($ "tv-power") "click" turn-on-tv)
(.addEventListener ($ "again-btn") "click"
                   #(do (start-race playing) (.scrollIntoView scene {:behavior "smooth" :block "center"})))
(defn- on-view-click [e]
  (when-let [btn (.closest (.-target e) "[data-view]")]
    (let [v (.. btn -dataset -view)]
      (when-not (identical? v view)
        (set-url-param! "view" (when (identical? v "data") "data"))
        (show-view v)))))
(.addEventListener ($ "view-switch") "click" on-view-click)
(.addEventListener ($ "chart-switch") "click"
                   (fn [e]
                     (when-let [btn (.closest (.-target e) "[data-chart]")]
                       (set! chart (.. btn -dataset -chart))
                       (update-data))))
(.observe (js/ResizeObserver. layout) road)
(layout)
(show-view view)
(when (some? skip-to) (skip-ahead (if (js/isNaN skip-to) 0 (js/Number skip-to))))
(js/requestAnimationFrame frame)
(translate/init ($ "lang") ($ "lang-note"))
