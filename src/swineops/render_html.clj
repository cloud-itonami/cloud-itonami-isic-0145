(ns swineops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: before this namespace
  existed there was NO demo page and no generator (`docs/` held only
  prose plus a product `index.html`). Everything the console shows is
  produced by driving the REAL actor stack at build time --
  `swineops.operation/build` -> `swineops.advisor` -> the independent
  `swineops.governor` -> the `swineops.phase` gate -> the disposition
  and audit facts that flow back out. No domain content on the page is
  hand-authored.

  MEASURED, NOT ASSUMED (2026-08-14, by running this namespace):

  * There is no langgraph StateGraph here. `swineops.operation/build`
    returns a plain invoke function -- `operation.cljc` documents the
    StateGraph wiring as deferred -- so the actor entry point IS
    `(operation/build store opts)`, and that is what is called. There is
    no `g/run*` to call.

  * `swineops.store/Store` is READ-ONLY: the protocol has exactly one
    method, `registered-facility`. The console counts those method
    signatures at render time (`store-protocol-sigs`) instead of
    claiming a shape. There is therefore no ledger inside the store to
    read back -- the audit ledger rendered here is the ordered
    concatenation of the `:audit` vectors the real runs returned, and
    the `:record` maps runs produced are shown as records that WOULD be
    written if a write path existed.

  * Approver attribution is DERIVED at render time, never asserted.
    `approver-attribution` scans every ledger fact and every produced
    record for approver-shaped keys. It deliberately does not read
    `:actor` as an approver: `operation/commit-fact` sets `:actor` from
    `(:actor-id context)`, i.e. the EXECUTING actor. The scan proves
    this rather than assuming it, by checking every `:actor` value
    observed against the actor-id this build passed in.

  * The findings section is derived from the runs too (`findings`), so a
    fix upstream makes a finding disappear from the page instead of
    leaving a stale accusation behind.

  Classification. A `:hold` disposition does NOT imply a governor
  refusal in this repo, and this is not a hypothetical: `phase/gate`'s
  default branch returns `{:disposition :hold :reason :unknown-phase}`
  for an unrecognised phase, and `operation.cljc` renders that through
  `governor/hold-fact`, producing a fact whose `:t` is `:governor-hold`
  while the verdict is clean (`:hard? false`, `:violations []`).
  Counting `:governor-hold` facts, or counting `:violations`, therefore
  both miscount. `classify` keys off the FACT TYPE first and then the
  verdict's `:hard?` flag, and `phase-gate-reasons` is computed by
  actually calling `phase/gate` across the phases and ops this build
  uses rather than hard-coding a list of reason keywords.

  Provenance of every literal in `scenarios` (the console must not
  assert anything this repo's own data does not contain):
    - facility id/name/barn/breed  `farm-001` / \"Sunrise Swine Farm\" /
                                   \"Barn 3, Pen 12\" / `landrace` from
                                   `swineops.sim/demo` and
                                   `test/swineops/store_test.cljc`
    - facility id/name             `farm-002` / \"New Swine Farm\" from
                                   `test/swineops/store_test.cljc`
                                   (seeded verbatim -- that record has
                                   no `:breed`, which is why the
                                   register shows an em dash there)
    - unregistered id              `no-such-farm` from
                                   `test/swineops/store_test.cljc`
    - breed ids                    `landrace` from
                                   `swineops.facts/breeds`
    - supply categories            `feed` / `veterinary-supply` /
                                   `biosecurity-equipment` and the
                                   500/500/1000 thresholds from
                                   `swineops.facts/supply-categories`
    - costs                        100 / 500 / 800 / 1000 / 1200 from
                                   `test/swineops/governor_test.cljc`
                                   or from the thresholds themselves
                                   (500 and 1000 are exact boundaries;
                                   `registry/cost-exceeds-threshold?`
                                   passes at the boundary)
    - herd counts                  120 and 0 from `governor_test` / `sim`
    - confidences                  0.5 and 0.95 from `governor_test`;
                                   0.7 is the literal
                                   `governor/confidence-floor`
    - concern text                 \"アフリカ豚熱(ASF)の疑い\" from
                                   `test/swineops/governor_test.cljc`
    - blocked ops                  `:administer-treatment` /
                                   `:order-slaughter` from
                                   `swineops.governor/blocked-ops`
  Three inputs are deliberate probes with no seed counterpart and are
  labelled as probes on the page: a negative herd count, the unknown op
  `:transfer-ownership`, and `:phase-unknown` (the phase gate's
  conservative default branch). One scenario swaps the advisor for an
  override advisor to exercise the Governor's `:no-execution` rule,
  which the stock mock advisor can never trigger; the advisor is an
  injected seam (`operation/run-operation`'s `:advisor` opt), so this is
  the supported way to show the Governor distrusting its own advisor.

  Build invariant: `-main` THROWS and writes nothing if the run produced
  zero HARD governor holds. The guard is satisfiable -- this build
  produces several -- and it is checked against the classifier above, so
  a phase-gate hold can never satisfy it.

  Determinism: no timestamps, no random ids, no UUIDs, no reliance on
  map or set iteration order (every fold over a set or map sorts first).
  Two runs are byte-identical.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [swineops.advisor :as advisor]
            [swineops.facts :as facts]
            [swineops.governor :as governor]
            [swineops.operation :as operation]
            [swineops.phase :as phase]
            [swineops.registry :as registry]
            [swineops.store :as store]))

;; ------------------------------- seed -------------------------------

(def ^:private seed-facilities
  "Facility records seeded into the real `swineops.store/mem-store`.
  `farm-001` is `swineops.sim/demo`'s facility verbatim; `farm-002` is
  `store_test`'s id+name verbatim. The Store docstring states facility
  data is opaque to it, so these ARE the whole records -- including the
  fact that `farm-002` carries no `:breed`."
  {"farm-001" {:id "farm-001" :name "Sunrise Swine Farm"
               :barn "Barn 3, Pen 12" :breed "landrace"}
   "farm-002" {:id "farm-002" :name "New Swine Farm"}})

(def ^:private unregistered-facility-id
  "An id deliberately NOT seeded, so the Governor's
  `:facility-not-registered` hard rule is exercised. The console proves
  it is absent by calling `store/registered-facility`, not by asserting."
  "no-such-farm")

(def ^:private operator-context
  {:actor-id "swine-ops-01" :role :farm-operator})

;; ------------------------------ advisor ------------------------------

(defrecord OverrideAdvisor [base overrides]
  advisor/Advisor
  (-advise [_this st request]
    (merge (advisor/-advise base st request) overrides)))

(defn- advisor-for
  "The stock `mock-advisor`, or -- when a scenario must show the Governor
  rejecting a MISBEHAVING advisor -- the same advisor with its proposal
  overridden."
  [overrides]
  (let [mock (advisor/mock-advisor)]
    (if (seq overrides) (->OverrideAdvisor mock overrides) mock)))

;; ----------------------------- scenarios -----------------------------

(def ^:private scenarios
  "Ordered scenario table. Each entry is fed to the real actor; nothing
  here records an expected outcome. Every disposition, verdict, rule and
  number rendered on the page is whatever the run actually returned."
  [;; --- clean commits -------------------------------------------------
   {:id "S01" :phase :phase-2
    :intent "登録済み豚舎への通常の頭数記録 (120頭・healthy)"
    :request {:op :log-herd-record :facility-id "farm-001" :count 120
              :health-status "healthy" :farrowing-count 11}}
   {:id "S02" :phase :phase-2
    :intent "登録済み豚舎への往診スケジュール"
    :request {:op :schedule-veterinary-visit :facility-id "farm-001"
              :reason "routine-check"}}
   {:id "S03" :phase :phase-3
    :intent "しきい値内の飼料発注 (100 ≦ 500)"
    :request {:op :order-supplies :facility-id "farm-001"
              :category "feed" :cost 100}}
   {:id "S04" :phase :phase-3
    :intent "しきい値ちょうどの飼料発注 (500 -- 境界は通過)"
    :request {:op :order-supplies :facility-id "farm-001"
              :category "feed" :cost 500}}
   {:id "S05" :phase :phase-3
    :intent "バイオセキュリティ設備発注、カテゴリ別しきい値ちょうど (1000)"
    :request {:op :order-supplies :facility-id "farm-002"
              :category "biosecurity-equipment" :cost 1000}}
   {:id "S06" :phase :phase-3
    :intent "確信度がフロアちょうど (0.7 -- 境界は通過)"
    :overrides {:confidence 0.7}
    :request {:op :log-herd-record :facility-id "farm-002" :count 120
              :health-status "healthy"}}

   ;; --- governor soft gates (escalate, NOT refusals) --------------------
   {:id "S07" :phase :phase-3
    :intent "動物衛生/バイオセキュリティ懸念のフラグ -- 常に人間へ"
    :request {:op :flag-animal-health-concern :facility-id "farm-001"
              :concern "アフリカ豚熱(ASF)の疑い"}}
   {:id "S08" :phase :phase-3
    :intent "しきい値超の獣医用品発注 (1200 > 500)"
    :request {:op :order-supplies :facility-id "farm-001"
              :category "veterinary-supply" :cost 1200}}
   {:id "S09" :phase :phase-3
    :intent "確信度がフロア未満 (0.5 < 0.7)"
    :overrides {:confidence 0.5}
    :request {:op :log-herd-record :facility-id "farm-001" :count 120
              :health-status "healthy"}}

   ;; --- phase gate (NOT governor refusals) -----------------------------
   {:id "S10" :phase :phase-0
    :intent "phase-0 は Governor が clean でも自律コミットさせない"
    :request {:op :log-herd-record :facility-id "farm-001" :count 120
              :health-status "healthy"}}
   {:id "S11" :phase :phase-1
    :intent "phase-1 は always-escalate op を clean でも人間へ回す"
    :request {:op :flag-animal-health-concern :facility-id "farm-001"
              :concern "アフリカ豚熱(ASF)の疑い"}}
   {:id "S12" :phase :phase-unknown
    :intent "未知の phase -- 保守的 hold (プローブ。Governor は clean)"
    :request {:op :log-herd-record :facility-id "farm-001" :count 120
              :health-status "healthy"}}

   ;; --- HARD governor refusals ----------------------------------------
   {:id "S13" :phase :phase-2
    :intent "未登録の豚舎を参照する提案"
    :request {:op :log-herd-record :facility-id "no-such-farm" :count 120
              :health-status "healthy"}}
   {:id "S14" :phase :phase-2
    :intent "直接治療の実施 -- 獣医/農場主の専権事項"
    :request {:op :administer-treatment :facility-id "farm-001"}}
   {:id "S15" :phase :phase-2
    :intent "と畜/淘汰の判断 -- 恒久ブロック"
    :request {:op :order-slaughter :facility-id "farm-001"}}
   {:id "S16" :phase :phase-2
    :intent "allowlist 外の操作 (プローブ)"
    :request {:op :transfer-ownership :facility-id "farm-001"}}
   {:id "S17" :phase :phase-2
    :intent "非正の頭数の記録 (0)"
    :request {:op :log-herd-record :facility-id "farm-001" :count 0}}
   {:id "S18" :phase :phase-2
    :intent "負の頭数の記録 (-5、プローブ)"
    :request {:op :log-herd-record :facility-id "farm-001" :count -5}}
   {:id "S19" :phase :phase-3
    :intent "advisor が直接実行を提案 (:effect :execute) -- advisor 差し替え"
    :overrides {:effect :execute}
    :request {:op :log-herd-record :facility-id "farm-001" :count 120
              :health-status "healthy"}}

   ;; --- the phase gate meeting a HARD verdict ---------------------------
   {:id "S20" :phase :phase-1
    :intent "phase-1 + always-escalate op + 未登録豚舎 (hard 違反あり)"
    :request {:op :flag-animal-health-concern :facility-id "no-such-farm"
              :concern "アフリカ豚熱(ASF)の疑い"}}])

;; ------------------------------- run ---------------------------------

(defn- run-all
  "Drive every scenario through the REAL actor. Returns the store used
  (so its state can be read back afterwards) and an ordered vector of
  run maps."
  []
  (let [st (store/mem-store {:initial-facilities seed-facilities})
        runs (mapv (fn [{:keys [id phase intent request overrides]}]
                     (let [actor (operation/build st {:advisor (advisor-for overrides)})
                           ctx (assoc operator-context :phase phase)
                           result (actor request ctx)]
                       {:id id :phase phase :intent intent :request request
                        :overrides overrides :context ctx :result result}))
                   scenarios)]
    {:store st :runs runs}))

;; --------------------------- classification ---------------------------

(def ^:private phase-gate-reasons
  "The reason keywords `swineops.phase/gate` can actually emit, DERIVED by
  calling it across the phases and ops this build uses. Not hard-coded:
  if the phase gate grows a reason, this set grows with it and the
  classifier keeps discriminating."
  (let [phases (into (sorted-set) (map :phase) scenarios)
        ops (into (sorted-set) (map (comp :op :request)) scenarios)]
    (into (sorted-set)
          (keep :reason)
          (for [ph phases, op ops, d [:commit :hold :escalate]]
            (phase/gate ph {:op op} d)))))

(defn- disposition-fact
  "The disposition fact is the LAST entry of the run's `:audit` vector --
  `operation/run-operation` appends exactly [advisor-trace
  disposition-fact]."
  [run]
  (-> run :result :audit last))

(defn- classify
  "Classify one run. Keys off the FACT TYPE first, then the verdict's
  `:hard?` flag -- never off `:violations` alone (an unknown-phase hold
  is a `:governor-hold` fact with an EMPTY `:violations`, and a hard
  refusal fact carries violations, so `:violations` discriminates the
  wrong way round for one of the two).

  :governor-hard-hold  a genuine Governor refusal -- non-negotiable
  :phase-hold          the phase gate held; the Governor was clean
  :governor-escalation the Governor's soft gate asked for a human
  :phase-escalation    the phase gate asked for a human
  :commit              committed
  :hard-verdict-lost   the Governor returned a HARD verdict and the
                       phase gate replaced it with something else"
  [run]
  (let [fact (disposition-fact run)
        verdict (-> run :result :verdict)
        hard? (boolean (:hard? verdict))
        t (:t fact)
        reason (:reason fact)]
    (cond
      (and (= t :governor-hold) hard?) :governor-hard-hold
      (= t :governor-hold) :phase-hold
      ;; a HARD verdict that did not land as a governor hold has been
      ;; overridden downstream -- surfaced, never silently folded in.
      hard? :hard-verdict-lost
      (and (= t :approval-requested) (contains? phase-gate-reasons reason))
      :phase-escalation
      (= t :approval-requested) :governor-escalation
      (= t :committed) :commit
      :else :unclassified)))

(def ^:private class-labels
  {:governor-hard-hold "Governor HARD hold"
   :phase-hold         "Phase gate hold"
   :governor-escalation "Governor escalation"
   :phase-escalation   "Phase gate escalation"
   :commit             "Commit"
   :hard-verdict-lost  "HARD verdict overridden"
   :unclassified       "Unclassified"})

(def ^:private class-css
  {:governor-hard-hold "critical"
   :phase-hold         "warn"
   :governor-escalation "warn"
   :phase-escalation   "warn"
   :commit             "ok"
   :hard-verdict-lost  "critical"
   :unclassified       "err"})

(def ^:private class-order
  [:commit :governor-escalation :phase-escalation :phase-hold
   :governor-hard-hold :hard-verdict-lost :unclassified])

;; ------------------------- derived measurements -------------------------

(def ^:private store-protocol-sigs
  "Method names on `swineops.store/Store`, read from the protocol itself
  at render time. One name means the store is read-only."
  (vec (sort (map name (keys (:sigs store/Store))))))

(def ^:private approver-key-re
  #"(?i)approv|sign-?off|authoriz|reviewer|endors|countersign|signatory")

(defn- approver-shaped-keys
  "Every key anywhere in `x` (maps nested to any depth) whose name looks
  like it names an approver."
  [x]
  (let [found (volatile! #{})]
    ((fn walk [v]
       (cond
         (map? v) (do (doseq [[k vv] v]
                        (when (and (keyword? k)
                                   (re-find approver-key-re (name k)))
                          (vswap! found conj k))
                        (walk vv))
                      nil)
         (sequential? v) (do (run! walk v) nil)
         :else nil))
     x)
    @found))

(defn- approver-attribution
  "Derive, at render time, whether a committed record or its audit fact
  retains WHO approved it. Never hard-codes a verdict about this repo.

  `:actor` is deliberately excluded from the approver scan and checked
  separately: `operation/commit-fact` sets it from `(:actor-id
  context)`, so it names the EXECUTING actor. `:actor-is-executor?` is
  the proof -- every `:actor` value observed is compared against the
  actor-id this build passed in."
  [runs]
  (let [facts (mapcat (comp :audit :result) runs)
        records (keep (comp :record :result) runs)
        actor-vals (into (sorted-set) (keep :actor) facts)
        expected (:actor-id operator-context)]
    {:fact-keys (vec (sort-by name (approver-shaped-keys (vec facts))))
     :record-keys (vec (sort-by name (approver-shaped-keys (vec records))))
     :record-count (count records)
     :record-top-keys (vec (sort-by name (into (sorted-set)
                                               (mapcat keys) records)))
     :actor-values (vec actor-vals)
     :actor-is-executor? (= actor-vals (sorted-set expected))
     :store-sigs store-protocol-sigs}))

(defn- findings
  "Defects DERIVED from this build's runs. Each finding is present only
  because the runs exhibited it; fixing the code upstream removes the
  finding from the page rather than leaving a stale accusation."
  [runs attribution store]
  (let [by-class (group-by classify runs)
        lost (get by-class :hard-verdict-lost)
        ;; an escalation labelled :always-escalate whose request op is
        ;; NOT in governor/always-escalate-ops was really a cost or
        ;; confidence escalation wearing the wrong label.
        mislabelled (filter (fn [run]
                              (let [f (disposition-fact run)]
                                (and (= :approval-requested (:t f))
                                     (= :always-escalate (:reason f))
                                     (not (contains? governor/always-escalate-ops
                                                     (-> run :request :op))))))
                            runs)
        commits (filter #(= :commit (classify %)) runs)
        ;; did any produced record reach the store?
        store-unchanged? (every? (fn [[fid rec]]
                                   (= rec (store/registered-facility store fid)))
                                 seed-facilities)]
    (cond-> []
      (seq lost)
      (conj {:id "F1"
             :severity "critical"
             :title "phase gate が Governor の HARD 違反を上書きする"
             :detail (str "phase/gate の :phase-1 分岐は入ってきた disposition を"
                          " 見ずに always-escalate op を :escalate へ書き換えるため、"
                          "Governor が hard 違反 (" (->> lost
                                                        (mapcat (comp :violations :verdict :result))
                                                        (map (comp name :rule))
                                                        (into (sorted-set))
                                                        (str/join ", "))
                          ") を返していても hold が消え、"
                          "監査ファクトは :approval-requested となって違反の痕跡を残さない。")
             :evidence (mapv :id lost)
             :fixed? false})

      (seq mislabelled)
      (conj {:id "F2"
             :severity "warn"
             :title "エスカレーション理由がコスト超過を :always-escalate と誤ラベルする"
             :detail (str "operation.cljc は理由を (:high-stakes? verdict) だけで"
                          " 決めるが、governor/check は :high-stakes? を"
                          " (or high-cost? always-escalate?) として立てる。"
                          "そのためコストしきい値超過のエスカレーションも"
                          " :always-escalate と記録され、監査ログから"
                          "「金額が理由か、op が理由か」を区別できない。")
             :evidence (mapv :id mislabelled)
             :fixed? false})

      (and (seq commits) store-unchanged? (= 1 (count store-protocol-sigs)))
      (conj {:id "F3"
             :severity "warn"
             :title "コミットされたレコードが SSoT に書き戻されない"
             :detail (str "Store プロトコルのメソッドは "
                          (str/join ", " store-protocol-sigs)
                          " の " (count store-protocol-sigs) " 本のみで、書き込み経路が無い。"
                          "run-operation は :record を返すが、実行後に store を読み直しても"
                          "シード時点から一切変化していない。"
                          "operation.cljc の docstring が言う「SSoT へのコミット」は"
                          "現状インメモリの戻り値に留まる。")
             :evidence (mapv :id commits)
             :fixed? false})

      (and (empty? (:record-keys attribution))
           (empty? (:fact-keys attribution))
           (pos? (:record-count attribution)))
      (conj {:id "F4"
             :severity "warn"
             :title "承認者が記録に残らない"
             :detail (str "この実行が生成した " (:record-count attribution)
                          " 件のレコードと全監査ファクトを走査したが、"
                          "承認者を名指しするキーは 1 つも無かった。"
                          ":actor は "
                          (if (:actor-is-executor? attribution)
                            (str "実行アクター (" (str/join ", " (:actor-values attribution))
                                 ") であることを実測で確認済みで、承認者ではない")
                            "承認者かどうか確認できなかった")
                          "。エスカレーションを再開する resume 経路自体が存在しないため、"
                          "承認は現状どこにも記録されない。")
             :evidence []
             :fixed? false}))))

;; ------------------------------- html --------------------------------

(defn- esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw->s
  "Render a keyword/value for display. Never emits the string \"nil\" --
  absent values become an em dash."
  [v]
  (cond
    (nil? v) "—"
    (keyword? v) (name v)
    (string? v) v
    :else (pr-str v)))

(defn- td [s] (str "<td>" s "</td>"))
(defn- th [s] (str "<th>" s "</th>"))

(defn- table [headers rows]
  (str "<table><thead><tr>"
       (str/join (map (comp th esc) headers))
       "</tr></thead><tbody>"
       (str/join (map #(str "<tr>" (str/join %) "</tr>") rows))
       "</tbody></table>"))

(defn- section [id title & body]
  (str "<section id=\"" id "\"><h2>" (esc title) "</h2>"
       (str/join body) "</section>"))

;; ------------------------------ sections ------------------------------

(defn- s-summary [runs]
  (let [classes (mapv classify runs)
        freqs (frequencies classes)
        rows (for [c class-order
                   :let [n (get freqs c 0)]
                   :when (pos? n)]
               [(td (str "<span class=\"badge " (class-css c) "\">"
                         (esc (class-labels c)) "</span>"))
                (td (str "<span class=\"num\">" n "</span>"))
                (td (esc (str/join ", " (map :id (filter #(= c (classify %)) runs)))))])]
    (section
     "summary" "実行サマリ"
     "<p class=\"subtitle\">シナリオ " (count runs) " 件を実 actor "
     "(<code>swineops.operation/build</code> → advisor → governor → phase gate) "
     "に通した結果。すべての行はその実行が返した値であり、期待値ではない。</p>"
     (table ["分類" "件数" "シナリオ"] rows)
     "<p class=\"muted\">分類は<strong>ファクト型を第一の手がかり</strong>にする。"
     "<code>:t :governor-hold</code> かつ verdict の <code>:hard?</code> が true "
     "のときだけ Governor による拒否と数える。"
     "未知 phase の hold も <code>:t :governor-hold</code> のファクトを生むが "
     "verdict は clean (<code>:violations []</code>) なので、"
     "<code>:violations</code> だけを見る数え方はこの 2 つを取り違える。</p>")))

(defn- s-register [store]
  (let [rows (for [fid (sort (keys seed-facilities))
                   :let [rec (store/registered-facility store fid)
                         breed (facts/breed-by-id (:breed rec))]]
               [(td (str "<code>" (esc fid) "</code>"))
                (td (esc (kw->s (:name rec))))
                (td (esc (kw->s (:barn rec))))
                (td (esc (if breed
                           (str (:name breed) " (" (:id breed) ")")
                           (kw->s (:breed rec)))))
                (td (str "<span class=\"badge ok\">登録済み</span>"))])
        absent (store/registered-facility store unregistered-facility-id)]
    (section
     "register" "施設レジスタ (実 Store から読み戻し)"
     "<p class=\"subtitle\">"
     "<code>store/mem-store</code> にシードし、"
     "<code>store/registered-facility</code> で読み直した内容。"
     "品種名は <code>swineops.facts/breed-by-id</code> の参照結果。</p>"
     (table ["facility-id" "名称" "豚舎/ピン" "品種" "状態"] rows)
     "<p class=\"muted\"><code>farm-002</code> の品種が空欄なのは、"
     "<code>test/swineops/store_test.cljc</code> のレコードをそのまま"
     "シードしており、そこに <code>:breed</code> が無いため。"
     "Store の docstring どおり施設データは Store にとって不透明で、"
     "形が揃っていることを前提にしていない。</p>"
     "<p class=\"" (if (nil? absent) "ok" "err") "\">"
     "<code>" (esc unregistered-facility-id) "</code> の照会結果: "
     "<code>" (esc (pr-str absent)) "</code> — "
     (if (nil? absent)
       "未登録であることを実測で確認 (アサートではない)。S13/S20 の hard 違反はこれに基づく。"
       "登録済みとして返った — このページの未登録前提は成立しない。")
     "</p>")))

(defn- s-rules []
  (let [cat-rows (for [cid (sort (keys facts/supply-categories))
                       :let [c (facts/supply-categories cid)]]
                   [(td (str "<code>" (esc cid) "</code>"))
                    (td (esc (:name c)))
                    (td (str "<span class=\"num amt\">" (:cost-threshold c) "</span>"))])
        rule-rows [[(th "許可 op (closed allowlist)")
                    (td (str/join ", " (map #(str "<code>" (esc (name %)) "</code>")
                                            (sort (map name governor/known-ops)))))]
                   [(th "恒久ブロック op")
                    (td (str/join ", " (map #(str "<code class=\"err\">" (esc (name %)) "</code>")
                                            (sort (map name governor/blocked-ops)))))]
                   [(th "常時エスカレーション op")
                    (td (str/join ", " (map #(str "<code class=\"warn\">" (esc (name %)) "</code>")
                                            (sort (map name governor/always-escalate-ops)))))]
                   [(th "確信度フロア")
                    (td (str "<span class=\"num\">" governor/confidence-floor "</span>"))]
                   [(th "既定コストしきい値")
                    (td (str "<span class=\"num amt\">" facts/default-cost-threshold "</span>"))]
                   [(th "phase gate が出しうる理由")
                    (td (str/join ", " (map #(str "<code>" (esc (name %)) "</code>")
                                            phase-gate-reasons)))]]]
    (section
     "rules" "Governor / phase gate の実定数"
     "<p class=\"subtitle\">すべて名前空間から直接読んだ値。"
     "phase の理由キーワードは <code>swineops.phase/gate</code> を"
     "実際に呼んで導出しており、ハードコードしていない。</p>"
     (table ["項目" "値"] rule-rows)
     "<h3>調達カテゴリ別のエスカレーションしきい値</h3>"
     "<p class=\"muted\"><code>registry/cost-exceeds-threshold?</code> は "
     "<code>&gt;</code> なので、しきい値ちょうどはエスカレーションしない。</p>"
     (table ["category" "名称" "しきい値"] cat-rows))))

(defn- s-scenarios [runs]
  (let [rows (for [run runs
                   :let [c (classify run)
                         res (:result run)
                         fact (disposition-fact run)
                         req (:request run)]]
               [(td (str "<code>" (esc (:id run)) "</code>"))
                (td (str "<code>" (esc (kw->s (:phase run))) "</code>"))
                (td (str "<code>" (esc (kw->s (:op req))) "</code>"))
                (td (str "<code>" (esc (kw->s (:facility-id req))) "</code>"))
                (td (esc (:intent run)))
                (td (str "<span class=\"num\">"
                         (esc (kw->s (-> res :verdict :confidence))) "</span>"))
                (td (str "<code>" (esc (kw->s (:disposition res))) "</code>"))
                (td (str "<span class=\"badge " (class-css c) "\">"
                         (esc (class-labels c)) "</span>"))
                (td (str "<code>" (esc (kw->s (:t fact))) "</code>"))])]
    (section
     "scenarios" "シナリオ実行結果"
     "<p class=\"subtitle\">" (count runs) " 件。"
     "<code>disposition</code> と <code>fact</code> 列は実行の戻り値そのもの。</p>"
     (table ["id" "phase" "op" "facility" "意図" "確信度" "disposition" "分類" "監査ファクト型"]
            rows))))

(defn- s-hard-holds [runs]
  (let [holds (filter #(= :governor-hard-hold (classify %)) runs)
        rows (for [run holds
                   v (-> run :result :verdict :violations)]
               [(td (str "<code>" (esc (:id run)) "</code>"))
                (td (str "<code>" (esc (kw->s (-> run :request :op))) "</code>"))
                (td (str "<code class=\"critical\">" (esc (name (:rule v))) "</code>"))
                (td (esc (:detail v)))])]
    (section
     "hard-holds" "Governor による HARD 拒否"
     "<p class=\"subtitle\">"
     "<strong>" (count holds) " 件</strong>の提案が Governor に拒否され、"
     "<code>:record</code> は <code>nil</code> のまま、SSoT へ何も向かわなかった。"
     "これらは phase の段階に関係なく覆せない (override 不可・恒久)。</p>"
     (table ["シナリオ" "op" "違反ルール" "Governor の説明"] rows)
     "<p class=\"muted\">拒否 " (count holds) " 件に対し違反行 " (count rows) " 件。"
     "拒否ファクトは自身が <code>:violations</code> を持つので、"
     "「違反を持つファクト = 拒否」とは数えていない — "
     "分類はファクト型と verdict の <code>:hard?</code> による。</p>")))

(defn- s-phase-gate [runs]
  (let [ph (filter #(#{:phase-hold :phase-escalation :hard-verdict-lost} (classify %)) runs)
        rows (for [run ph
                   :let [fact (disposition-fact run)
                         c (classify run)]]
               [(td (str "<code>" (esc (:id run)) "</code>"))
                (td (str "<code>" (esc (kw->s (:phase run))) "</code>"))
                (td (str "<code>" (esc (kw->s (:op (:request run)))) "</code>"))
                (td (str "<code>" (esc (kw->s (or (:reason fact) (:phase-reason fact))))
                         "</code>"))
                (td (str "<code>" (esc (kw->s (-> run :result :verdict :hard?))) "</code>"))
                (td (str "<span class=\"badge " (class-css c) "\">"
                         (esc (class-labels c)) "</span>"))])]
    (section
     "phase-gate" "phase gate による保留 (Governor の拒否ではない)"
     "<p class=\"subtitle\">" (count ph) " 件。"
     "これらは Governor が拒否したのではなく、ロールアウト段階が"
     "自律コミットを認めなかった結果。"
     "<code>verdict :hard?</code> 列がその区別の根拠。</p>"
     (table ["シナリオ" "phase" "op" "phase 理由" "verdict :hard?" "分類"] rows))))

(defn- s-ledger [runs]
  (let [rows (for [run runs
                   [i fact] (map-indexed vector (-> run :result :audit))]
               [(td (str "<code>" (esc (:id run)) "." (inc i) "</code>"))
                (td (str "<code>" (esc (kw->s (:t fact))) "</code>"))
                (td (str "<code>" (esc (kw->s (:op fact))) "</code>"))
                (td (str "<code>" (esc (kw->s (or (:subject fact) (:facility-id fact))))
                         "</code>"))
                (td (str "<code>" (esc (kw->s (:actor fact))) "</code>"))
                (td (esc (or (:proposal-summary fact)
                             (:summary fact)
                             (when-let [b (seq (:basis fact))]
                               (str/join ", " (map kw->s b)))
                             (kw->s (:reason fact)))))])]
    (section
     "ledger" "監査台帳 (実行が返した :audit の連結)"
     "<p class=\"subtitle\">" (count rows) " ファクト。"
     "Store には書き込み経路が無いため、これは store から読み戻したものではなく、"
     "各実行が返した <code>:audit</code> ベクタを実行順に連結したもの。</p>"
     (table ["seq" "型" "op" "subject" "actor" "内容"] rows))))

(defn- s-records [runs]
  (let [rows (for [run runs
                   :let [rec (-> run :result :record)]
                   :when rec]
               [(td (str "<code>" (esc (:id run)) "</code>"))
                (td (str "<code>" (esc (kw->s (:effect rec))) "</code>"))
                (td (str "<code>" (esc (str/join "/" (:path rec))) "</code>"))
                (td (str "<code>" (esc (pr-str (into (sorted-map) (:value rec)))) "</code>"))
                (td (str "<code>" (esc (if (= (:value rec) (:payload rec))
                                         ":value と同一"
                                         (pr-str (into (sorted-map) (:payload rec)))))
                         "</code>"))])]
    (section
     "records" "コミットされたレコード"
     "<p class=\"subtitle\">" (count rows) " 件。"
     "<code>operation/commit-record</code> が返した内容。"
     "Store に書き込み経路が無いため、これらは<strong>戻り値であって"
     "永続化されたレジスタの状態ではない</strong> (下の所見 F3)。</p>"
     (table ["シナリオ" ":effect" ":path" ":value" ":payload"] rows))))

(defn- s-attribution [a]
  (section
   "attribution" "承認者の帰属 (レンダリング時に走査して導出)"
   "<p class=\"subtitle\">このリポジトリが承認者を保持するかどうかを"
   "決め打ちせず、実行が生んだファクトとレコードを実際に走査して求めた結果。</p>"
   (table
    ["測定項目" "結果"]
    [[(th "Store プロトコルのメソッド")
      (td (str "<code>" (esc (str/join ", " (:store-sigs a))) "</code> ("
               (count (:store-sigs a)) " 本)"))]
     [(th "生成されたレコード数")
      (td (str "<span class=\"num\">" (:record-count a) "</span>"))]
     [(th "レコードの最上位キー")
      (td (str "<code>" (esc (str/join ", " (map kw->s (:record-top-keys a)))) "</code>"))]
     [(th "レコード中の承認者らしきキー")
      (td (if (seq (:record-keys a))
            (str "<code>" (esc (str/join ", " (map kw->s (:record-keys a)))) "</code>")
            "<span class=\"err\">0 件</span>"))]
     [(th "監査ファクト中の承認者らしきキー")
      (td (if (seq (:fact-keys a))
            (str "<code>" (esc (str/join ", " (map kw->s (:fact-keys a)))) "</code>")
            "<span class=\"err\">0 件</span>"))]
     [(th "観測された <code>:actor</code> 値")
      (td (str "<code>" (esc (str/join ", " (:actor-values a))) "</code>"))]
     [(th "<code>:actor</code> は実行アクターか")
      (td (if (:actor-is-executor? a)
            (str "<span class=\"ok\">はい</span> — 観測された値は"
                 "このビルドが渡した <code>:actor-id</code> と完全に一致した")
            "<span class=\"warn\">いいえ</span> — 実行アクター以外の値が混ざった"))]])
   "<p class=\"muted\">走査は <code>:actor</code> を承認者候補から"
   "<strong>意図的に除外</strong>している。"
   "<code>operation/commit-fact</code> は <code>:actor</code> を"
   "<code>(:actor-id context)</code> から設定しており、"
   "承認者ではなく実行アクターを指す。"
   "テストデータ上ではこの 2 つが同じ値になりうるため、"
   "上の行で実測して区別している。</p>"))

(defn- s-findings [fs]
  (section
   "findings" "この実行で観測された所見"
   "<p class=\"subtitle\">" (count fs) " 件。"
   "いずれも上の実行結果から導出しており、固定文ではない — "
   "上流が直れば次のビルドでこの節から消える。"
   "レンダリングの都合で Governor を書き換えることはしていない。</p>"
   (str/join
    (for [f fs]
      (str "<div class=\"card\">"
           "<h3><span class=\"badge " (esc (:severity f)) "\">"
           (esc (:id f)) "</span> " (esc (:title f)) "</h3>"
           "<p>" (esc (:detail f)) "</p>"
           (if (seq (:evidence f))
             (str "<p class=\"muted\">根拠シナリオ: <code>"
                  (esc (str/join ", " (:evidence f))) "</code></p>")
             "")
           "</div>")))))

(defn- s-provenance [runs]
  (section
   "provenance" "出所と検証"
   (table
    ["項目" "内容"]
    [[(th "actor エントリポイント")
      (td "<code>swineops.operation/build</code> — langgraph StateGraph は未実装 (<code>operation.cljc</code> が deferred と明記)。素の invoke 関数を呼んでいる。")]
     [(th "advisor")
      (td "<code>swineops.advisor/mock-advisor</code>。1 件のみ override advisor で <code>:effect</code> を差し替え、Governor の <code>:no-execution</code> を発火させた。")]
     [(th "施設シード")
      (td "<code>swineops.sim/demo</code> と <code>test/swineops/store_test.cljc</code> のレコードをそのまま使用。")]
     [(th "しきい値・フロア")
      (td "<code>swineops.facts/supply-categories</code>、<code>swineops.governor/confidence-floor</code> から直接読み出し。")]
     [(th "決定性")
      (td "タイムスタンプ・UUID・乱数を使わない。集合/マップ由来の列はすべてソート済み。同一入力で 2 回ビルドするとバイト一致する。")]
     [(th "ビルド不変条件")
      (td (str "HARD な Governor 拒否が 0 件のとき <code>-main</code> は例外を投げ、"
               "ファイルを書かない。今回の実行では "
               (count (filter #(= :governor-hard-hold (classify %)) runs))
               " 件検出したため書き出した。"))]])))

;; ------------------------------- page --------------------------------

(defn- render
  [{:keys [store runs]}]
  (let [a (approver-attribution runs)
        fs (findings runs a store)
        hard (filter #(= :governor-hard-hold (classify %)) runs)]
    (str "<!DOCTYPE html>\n"
         "<html lang=\"ja\"><head>"
         "<meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
         "<meta name=\"color-scheme\" content=\"light\">"
         "<meta name=\"theme-color\" content=\"#ffffff\">"
         "<title>オペレータコンソール | cloud-itonami-isic-0145 (swineops)</title>"
         "<meta name=\"description\" content=\"養豚経営調整アクターの実行結果 — 実 actor をビルド時に駆動して生成した監査コンソール。\">"
         "<style>" (jp-go-dds.skin/dds+skin) "</style>"
         "</head><body>"
         "<div class=\"bar\">"
         "<span class=\"badge\">ISIC 0145</span>"
         "<span class=\"badge\">swineops</span>"
         "<span class=\"badge " (if (seq hard) "critical" "err") "\">"
         "HARD 拒否 " (count hard) " 件</span>"
         "</div>"
         "<h1>養豚経営調整アクター — オペレータコンソール</h1>"
         "<p class=\"subtitle\">このページはビルド時に "
         "<code>clojure -M:render-html</code> が実 actor "
         "(advisor → 独立 Governor → phase gate) を実行して生成した。"
         "掲載されている施設・判定・違反・数値はすべてその実行の戻り値であり、"
         "手書きのドメイン内容は含まない。</p>"
         "<div class=\"banner\">"
         "<p><strong>読み方。</strong> "
         "<em>Governor による拒否</em> と <em>phase gate による保留</em> は"
         "別物として数えている。前者は提案そのものが規則違反で覆せないもの、"
         "後者はロールアウト段階がまだ自律コミットを許していないだけのもの。"
         "この 2 つは <code>:hold</code> という同じ disposition に落ちることがあり、"
         "未知 phase の保留は <code>:t :governor-hold</code> というファクト型まで"
         "共有する。分類はファクト型と verdict の <code>:hard?</code> を"
         "見て区別している。</p></div>"
         (s-summary runs)
         (s-hard-holds runs)
         (s-phase-gate runs)
         (s-scenarios runs)
         (s-register store)
         (s-rules)
         (s-records runs)
         (s-ledger runs)
         (s-attribution a)
         (s-findings fs)
         (s-provenance runs)
         "<footer>"
         "<p>cloud-itonami-isic-0145 — Swine-Farm Operations Coordination "
         "(ISIC 0145, raising of swine/pigs). "
         "生成元: <code>src/swineops/render_html.clj</code>。"
         "再生成: <code>clojure -M:render-html</code>。</p>"
         "<p>スタイルは "
         "<a href=\"https://github.com/kotoba-lang/jp-go-digital-design-system\">jp-go-dds</a> "
         "(デジタル庁デザインシステムの vendored CSS + skin)。</p>"
         "</footer></body></html>\n")))

;; ------------------------------- main --------------------------------

(def ^:private default-out "docs/samples/operator-console.html")

(defn -main
  "Render the operator console from a real actor run.

  THROWS and writes nothing if the run produced zero HARD governor
  holds. The guard is deliberately narrow: it counts only
  `:governor-hard-hold`, so a phase-gate hold -- which shares the
  `:governor-hold` fact type -- can never satisfy it."
  [& [out]]
  (let [{:keys [store runs] :as world} (run-all)
        by-class (frequencies (map classify runs))
        hard (get by-class :governor-hard-hold 0)
        unclassified (get by-class :unclassified 0)
        out-file (io/file (or out default-out))]
    (when (pos? unclassified)
      (throw (ex-info "分類できない実行がある — 分類器が実装より遅れている"
                      {:unclassified unclassified :by-class by-class})))
    (when (zero? hard)
      (throw (ex-info (str "HARD な Governor 拒否が 0 件のため書き出さない。"
                           "実 actor が拒否を 1 件も返さないページは、"
                           "Governor が働いていることの証拠にならない。")
                      {:by-class by-class :scenarios (count runs)})))
    (when-not (= (reduce + (vals by-class)) (count runs))
      (throw (ex-info "分類が実行数と一致しない" {:by-class by-class})))
    (io/make-parents out-file)
    (spit out-file (render world))
    (println (str "wrote " (.getPath out-file)
                  "  scenarios=" (count runs)
                  "  hard-governor-holds=" hard
                  "  phase-holds=" (get by-class :phase-hold 0)
                  "  phase-escalations=" (get by-class :phase-escalation 0)
                  "  governor-escalations=" (get by-class :governor-escalation 0)
                  "  commits=" (get by-class :commit 0)
                  "  hard-verdict-lost=" (get by-class :hard-verdict-lost 0)
                  "  bytes=" (.length out-file)))
    ;; touch `registry` so the ns require is not dead: the console's
    ;; boundary claims (500 / 1000 / 0.7 pass at the boundary) are the
    ;; same predicates the Governor uses.
    (when (or (registry/cost-exceeds-threshold? 500 500)
              (registry/confidence-below-floor?
               governor/confidence-floor governor/confidence-floor)
              (registry/herd-count-non-positive? 1))
      (throw (ex-info "境界の前提が実装と食い違っている" {})))))
