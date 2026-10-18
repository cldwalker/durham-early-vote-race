(ns runner
  "SVG runner figures with jointed limbs. Poses are computed each frame so
  stride rate and amplitude can follow each runner's current speed.")

(def viewbox {:x -34 :y -58 :w 68 :h 98})

(def looks
  [{:shirt "#2563eb" :shorts "#1e3a8a" :skin "#f0c08a" :hair "#4a2c17"
    :shoe "#111827" :sole "#f8fafc" :band "#facc15" :ponytail false}
   {:shirt "#db2777" :shorts "#3b0764" :skin "#9a5b34" :hair "#1c1310"
    :shoe "#f8fafc" :sole "#db2777" :band nil :ponytail true}
   ;; The runner a visitor controls
   {:shirt "#16a34a" :shorts "#14532d" :skin "#c68642" :hair "#2b1b10"
    :shoe "#111827" :sole "#facc15" :band "#f8fafc" :ponytail false}])

(defn- shade [hex amount]
  (let [n (js/parseInt (subs hex 1) 16)
        ch #(js/Math.round (* % (+ 1 amount)))]
    (str "rgb(" (ch (bit-shift-right n 16)) "," (ch (bit-and (bit-shift-right n 8) 255)) ","
         (ch (bit-and n 255)) ")")))

(defn- leg [id {:keys [skin shorts shoe sole]}]
  #html [:g {:data-p (str "thigh" id)}
         [:line {:y2 17 :stroke skin :stroke-width 6 :stroke-linecap "round"}]
         [:line {:y2 7 :stroke shorts :stroke-width 9 :stroke-linecap "round"}]
         [:g {:transform "translate(0 17)"}
          [:g {:data-p (str "shin" id)}
           [:line {:y2 17 :stroke skin :stroke-width 5.2 :stroke-linecap "round"}]
           [:rect {:x -3.5 :y 15.5 :width 10.5 :height 5 :rx 2.3 :fill shoe}]
           [:rect {:x -3.5 :y 19.2 :width 10.5 :height 1.6 :rx 0.8 :fill sole}]]]])

(defn- arm [id {:keys [skin shirt]}]
  #html [:g {:transform "translate(4 -25)"}
         [:g {:data-p (str "arm" id)}
          [:line {:y2 12 :stroke skin :stroke-width 4.6 :stroke-linecap "round"}]
          [:line {:y2 5 :stroke shirt :stroke-width 6.5 :stroke-linecap "round"}]
          [:g {:transform "translate(0 12)"}
           [:g {:data-p (str "fore" id)}
            [:line {:y2 10 :stroke skin :stroke-width 4.2 :stroke-linecap "round"}]
            [:circle {:cy 10.5 :r 2.6 :fill skin}]]]]])

(defn- svg-markup [{:keys [skin shirt shorts hair band ponytail] :as look} bib]
  (let [back (js/Object.assign {} look {:skin (shade skin -0.18) :shirt (shade shirt -0.2) :shorts (shade shorts -0.2)})
        {:keys [x y w h]} viewbox]
    #html [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox (str x " " y " " w " " h) :aria-hidden "true"}
           [:ellipse {:cx 3 :cy 38.6 :rx 13 :ry 2.4 :fill "rgba(0,0,0,.28)" :data-p "shadow"}]
           [:g {:data-p "body"}
            (when ponytail
              #html [:g {:transform "translate(-0.5 -44)"}
                     [:g {:data-p "tail"}
                      [:path {:d "M0 -1 C-6 -2 -10 3 -12 11 C-8 8 -5 7 0 4 Z" :fill hair}]]])
            (arm "B" back)
            (leg "B" back)
            [:line {:x1 0 :y1 -3 :x2 4 :y2 -25 :stroke shirt :stroke-width 13 :stroke-linecap "round"}]
            [:g {:transform "translate(2 -14) rotate(10)"}
             [:rect {:x -5 :y -3.6 :width 10 :height 7.2 :rx 1 :fill "#fff"}]
             [:text {:y 1.6 :font-size 4.3 :font-weight 800 :text-anchor "middle"
                     :font-family "system-ui, sans-serif" :fill "#111827"} bib]]
            (leg "F" look)
            [:line {:x1 4 :y1 -25 :x2 5 :y2 -31 :stroke skin :stroke-width 4.5 :stroke-linecap "round"}]
            [:circle {:cx 4.6 :cy -41.6 :r 8.4 :fill hair}]
            [:circle {:cx 6.2 :cy -39.5 :r 7.8 :fill skin}]
            [:circle {:cx 4.3 :cy -39 :r 1.7 :fill (shade skin -0.15)}]
            [:circle {:cx 10.4 :cy -40.6 :r 1.05 :fill "#1f2937"}]
            [:path {:d "M9.6 -36.4 q1.6 1.1 3.1 -0.2" :stroke "#7c2d12" :stroke-width 0.8 :fill "none"
                    :stroke-linecap "round"}]
            (when band #html [:path {:d "M-1.4 -42 Q6.2 -48.5 13.8 -42" :stroke band :stroke-width 2.6 :fill "none"}])
            (when ponytail #html [:circle {:cx -0.5 :cy -44 :r 1.9 :fill shirt}])
            (arm "F" look)]]))

(def ^:private joints [:thighF :shinF :thighB :shinB :armF :foreF :armB :foreB :tail])

(defn create-runner
  "Creates a runner figure for `look` wearing bib text `bib`. Returns `{:el :set-pose}`,
  where `:set-pose` applies a pose from [[run-pose]] or [[celebrate-pose]]."
  [look bib]
  (let [holder (js/document.createElement "div")
        _ (set! (.-innerHTML holder) (str (svg-markup look bib)))
        el (.-firstElementChild holder)
        parts {}]
    (doseq [p (.querySelectorAll el "[data-p]")] (assoc! parts (.. p -dataset -p) p))
    {:el el
     :set-pose (fn [pose]
                 (doseq [j joints]
                   (when-let [part (get parts j)]
                     (.setAttribute part "transform" (str "rotate(" (.toFixed (get pose j) 1) ")"))))
                 (.setAttribute (:body parts) "transform" (str "translate(0 " (.toFixed (:bob pose) 2) ")")))}))

(defn run-pose
  "Returns joint angles for stride phase `p` in radians and `amp` from 0
  (standing) to 1 (full sprint). Positive angles swing a limb backwards for a
  runner facing right."
  [p amp]
  (let [s (js/Math.sin p)
        c (js/Math.cos p)
        fore (- (+ 20 (* 75 amp)))]
    {:thighF (* -34 s amp) :shinF (* amp (+ 6 (* 75 (max 0 c))))
     :thighB (* 34 s amp) :shinB (* amp (+ 6 (* 75 (max 0 (- c)))))
     :armF (* 38 s amp) :foreF fore
     :armB (* -38 s amp) :foreB fore
     :bob (* -2.5 amp (abs s))
     :tail (+ 15 (* amp (+ 25 (* 12 (js/Math.sin (* 2 p))))))}))

(defn celebrate-pose
  "Returns joint angles for jumping with both arms raised, `t` seconds into
  the celebration."
  [t]
  (let [j (abs (js/Math.sin (* t 5.5)))
        wave (* 10 (js/Math.sin (* t 11)))]
    {:thighF (* -25 j) :shinF (* 50 j)
     :thighB (* 10 j) :shinB (* 45 j)
     :armF (+ 130 wave) :foreF 12
     :armB (- -130 wave) :foreB -12
     :bob (* -10 j)
     :tail (+ 20 (* 30 j))}))
