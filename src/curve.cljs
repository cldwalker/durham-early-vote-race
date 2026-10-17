(ns curve)

(defn monotone-curve
  "Returns a fn of fractional day `x` that gives `{:value :slope}` (slope per
  day) for a monotone cubic (Fritsch–Carlson) interpolation through the
  cumulative totals `ys` at integer day boundaries. Totals stay exact at each
  day boundary, never run backwards, and speed changes smoothly instead of
  jumping once per day. With `:end-at-rest`, the curve eases to a stop at its
  last point."
  [ys {:keys [end-at-rest]}]
  (let [n (count ys)
        d (.map (.slice ys 1) (fn [y k] (- y (aget ys k))))
        ;; m[0] stays 0 so runners accelerate away from the start line
        m (.fill (js/Array. n) 0)]
    (aset m (dec n) (if end-at-rest 0 (or (aget d (- n 2)) 0)))
    (dotimes [k (- n 2)]
      (let [a (aget d k) b (aget d (inc k))]
        (aset m (inc k) (if (<= (* a b) 0) 0 (/ (+ a b) 2)))))
    (dotimes [k (dec n)]
      (let [dk (aget d k)
            a (/ (aget m k) dk)
            b (/ (aget m (inc k)) dk)
            s (+ (* a a) (* b b))]
        (cond (zero? dk) (do (aset m k 0) (aset m (inc k) 0))
              (> s 9) (let [tau (/ 3 (js/Math.sqrt s))]
                        (aset m k (* tau a dk))
                        (aset m (inc k) (* tau b dk))))))
    (fn [x]
      (cond
        (<= x 0) {:value (aget ys 0) :slope (aget m 0)}
        (>= x (dec n)) {:value (aget ys (dec n)) :slope (aget m (dec n))}
        :else
        (let [i (js/Math.floor x)
              h (- x i)
              h2 (* h h)
              h3 (* h2 h)
              y0 (aget ys i) y1 (aget ys (inc i)) m0 (aget m i) m1 (aget m (inc i))]
          {:value (+ (* (+ (* 2 h3) (* -3 h2) 1) y0) (* (+ h3 (* -2 h2) h) m0)
                     (* (+ (* -2 h3) (* 3 h2)) y1) (* (- h3 h2) m1))
           :slope (+ (* (- (* 6 h2) (* 6 h)) y0) (* (+ (* 3 h2) (* -4 h) 1) m0)
                     (* (+ (* -6 h2) (* 6 h)) y1) (* (- (* 3 h2) (* 2 h)) m1))})))))
