(ns pm.model.content
  "Content-expression parser and ordered automata, ported from content.ts.
  Matches hold an immutable graph plus a state index (no cyclic values)."
  (:require [clojure.string :as str]))

(defn- fail [message] (throw (ex-info message {})))

(defn- parse-expr [source types]
  (let [tokens (vec (re-seq #"\w+|[^\s\w]" source))
        pos (atom 0) inline (atom nil)]
    (letfn [(peek-token [] (get tokens @pos))
            (eat [token] (when (= token (peek-token)) (swap! pos inc) true))
            (number! [] (let [t (peek-token)]
                         (when-not (and t (re-matches #"\d+" t)) (fail "Expected number"))
                         (swap! pos inc) (Long/parseLong t)))
            (atom! []
              (if (eat "(")
                (let [e (choice!)] (when-not (eat ")") (fail "Missing closing paren")) e)
                (let [n (peek-token)
                      found (or (when-let [t (get types n)] [t])
                                (seq (filter #(some #{n} (:groups %)) (vals types))))]
                  (when-not (and n (re-matches #"\w+" n) found)
                    (fail (str "No node type or group '" n "' found")))
                  (swap! pos inc)
                  (doseq [t found]
                    (if (nil? @inline) (reset! inline (:inline? t))
                        (when (not= @inline (:inline? t)) (fail "Mixing inline and block content"))))
                  [:choice (mapv #(vector :name %) found)])))
            (sub! []
              (loop [e (atom!)]
                (cond
                  (eat "*") (recur [:star e])
                  (eat "+") (recur [:plus e])
                  (eat "?") (recur [:opt e])
                  (eat "{") (let [lo (number!)
                                   hi (if (eat ",") (if (= "}" (peek-token)) -1 (number!)) lo)]
                               (when-not (eat "}") (fail "Unclosed braced range"))
                               (recur [:range e lo hi]))
                  :else e)))
            (seq! [] (loop [es [(sub!)]]
                       (if (and (peek-token) (not (#{")" "|"} (peek-token))))
                         (recur (conj es (sub!))) [:seq es])))
            (choice! [] (loop [es [(seq!)]]
                          (if (eat "|") (recur (conj es (seq!))) [:choice es])))]
      (when (seq tokens)
        (let [e (choice!)]
          (when (peek-token) (fail "Unexpected trailing text")) e)))))

(defn- nfa [expr]
  (let [graph (atom [[]])]
    (letfn [(state! [] (let [i (count @graph)] (swap! graph conj []) i))
            (edge! [from term to]
              (let [i (count (get @graph from))]
                (swap! graph update from conj {:term term :to to}) [from i]))
            (connect! [edges to] (doseq [[s i] edges] (swap! graph assoc-in [s i :to] to)))
            (compile! [[kind value lo hi] from]
              (case kind
                :name [(edge! from value nil)]
                :choice (vec (mapcat #(compile! % from) value))
                :seq (loop [es value cur from]
                       (let [edges (compile! (first es) cur)]
                         (if (next es)
                           (let [n (state!)] (connect! edges n) (recur (next es) n)) edges)))
                :star (let [n (state!)] (edge! from nil n)
                           (connect! (compile! value n) n) [(edge! n nil nil)])
                :plus (let [n (state!)] (connect! (compile! value from) n)
                           (connect! (compile! value n) n) [(edge! n nil nil)])
                :opt (into [(edge! from nil nil)] (compile! value from))
                :range (let [cur (reduce (fn [cur _] (let [n (state!)]
                                                     (connect! (compile! value cur) n) n))
                                        from (range lo))
                             cur (if (= hi -1)
                                   (do (connect! (compile! value cur) cur) cur)
                                   (reduce (fn [cur _] (let [n (state!)]
                                                        (edge! cur nil n)
                                                        (connect! (compile! value cur) n) n))
                                           cur (range lo hi)))]
                         [(edge! cur nil nil)])))]
      (let [edges (if expr (compile! expr 0) [(edge! 0 nil nil)])]
        (connect! edges (state!)))
      @graph)))

(defn- null-from [graph start]
  (let [seen (atom #{}) result (atom #{})]
    (letfn [(scan [n]
              (when-not (@seen n)
                (swap! seen conj n)
                (let [edges (get graph n)]
                  (when-not (and (= 1 (count edges)) (nil? (:term (first edges))))
                    (swap! result conj n))
                  (doseq [{:keys [term to]} edges :when (nil? term)] (scan to)))))]
      (scan start) (vec (sort > @result)))))

(defn- dfa [nfa]
  (let [labels (atom {}) graph (atom [])]
    (letfn [(explore [states]
              (if-let [i (get @labels states)] i
                (let [i (count @graph)
                      terms (reduce
                             (fn [out {:keys [term to]}]
                               (if-not term out
                                 (let [j (first (keep-indexed #(when (= term (first %2)) %1) out))
                                       reachable (null-from nfa to)]
                                   (if j (update-in out [j 1] into reachable)
                                       (conj out [term (set reachable)])))))
                             [] (mapcat #(get nfa %) states))]
                  (swap! labels assoc states i)
                  (swap! graph conj {:valid-end? (boolean (some #{(dec (count nfa))} states))})
                  (let [edges (mapv (fn [[t ns]] [t (explore (vec (sort > ns)))]) terms)]
                    (swap! graph assoc-in [i :next] edges))
                  i)))]
      (explore (null-from nfa 0)) {:graph @graph :state 0})))

(defn valid-end? [m] (boolean (get-in m [:graph (:state m) :valid-end?])))
(defn edges [m] (get-in m [:graph (:state m) :next]))
(defn generatable? [t]
  (and (not= "text" (:name t))
       (every? #(contains? % :default) (vals (get-in t [:spec :attrs])))))
(defn parse [source types]
  (let [m (dfa (nfa (parse-expr source types)))]
    (doseq [s (:graph m)]
      (when (and (not (:valid-end? s)) (not-any? #(generatable? (first %)) (:next s)))
        (fail "Only non-generatable nodes in a required position")))
    m))
(defn match-type [m t]
  (when m (when-let [[_ n] (first (filter #(= t (first %)) (edges m)))] (assoc m :state n))))
(defn match-fragment
  ([m fragment] (match-fragment m fragment 0 (count fragment)))
  ([m fragment start end] (reduce #(match-type %1 (:type %2)) m (subvec (vec fragment) start end))))
(defn default-type [m] (some #(when (generatable? (first %)) (first %)) (edges m)))
(defn inline-content? [m] (boolean (:inline? (ffirst (edges m)))))
(defn fill-before
  ([m after] (fill-before m after false))
  ([m after to-end] (fill-before m after to-end 0))
  ([m after to-end start]
   (when m
     (let [seen (atom #{(:state m)})]
       (letfn [(search [m types]
                 (let [finished (match-fragment m after start (count after))]
                   (if (and finished (or (not to-end) (valid-end? finished)))
                     (mapv #((requiring-resolve 'pm.model.schema/create-and-fill) %) types)
                     (some (fn [[t n]]
                             (when (and (generatable? t) (not (@seen n)))
                               (swap! seen conj n)
                               (search (assoc m :state n) (conj types t)))) (edges m)))))]
         (search m []))))))
