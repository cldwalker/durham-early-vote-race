(ns data)

(defn- lines [text]
  (.filter (.map (.split text #"\r?\n") #(.trim %)) #(not (identical? "" %))))

(defn parse-counts
  "Parses early-vote-counts.csv: a header row of years followed by one
  comma-delimited row per day. A year still in progress has empty or missing
  cells for the days that haven't happened yet, and a year with fewer early
  voting days has trailing counts of 0. Returns `{:years :days}`, where each
  year's `:data-days` is how many days it has data for and `:vote-days` is how
  many days it had early voting."
  [text]
  (let [all (.map (lines text) (fn [l] (.map (.split l ",") #(.trim %))))
        rows (.slice all 1)
        days (count rows)]
    {:days days
     :years (.map (first all)
                  (fn [year i]
                    ;; JS truthiness: an empty or missing cell is falsy
                    (let [data-days (inc (.findLastIndex rows #(aget % i)))
                          vote-days (inc (.findLastIndex rows #(pos? (js/Number (aget % i)))))
                          sums (.reduce (.slice rows 0 data-days)
                                        (fn [acc row] (.concat acc (+ (last acc) (js/Number (or (aget row i) 0)))))
                                        [0])]
                      {:name year :data-days data-days :vote-days vote-days
                       :complete (== data-days days) :cumulative sums})))}))

(defn parse-phrases
  "Parses voter-phrases.csv: one phrase per line. A line wrapped in double
  quotes is unquoted, and a literal `\\n` marks a line break within a phrase."
  [text]
  (.map (lines text)
        (fn [l]
          (-> (if (.test #"^\".*\"$" l) (.replaceAll (.slice l 1 -1) "\"\"" "\"") l)
              (.replaceAll "\\n" "\n")))))
