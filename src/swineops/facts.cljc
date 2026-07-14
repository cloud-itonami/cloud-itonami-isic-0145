(ns swineops.facts
  "Reference facts for swine-farm operations coordination: supply category
  cost policy, breed classification, and biosecurity/notifiable-disease
  reference vocabulary. This namespace contains pure lookup functions for
  domain reference data -- the Governor and Advisor consult these instead
  of inventing thresholds. Mirrors `cattleops.facts`
  (cloud-itonami-isic-0141) in shape.")

(def supply-categories
  "Procurement categories this actor may propose orders for, and the
  default cost threshold above which an order proposal must escalate for
  human sign-off (farm operator/veterinarian)."
  {"feed"
   {:id "feed" :name "飼料" :cost-threshold 500}

   "veterinary-supply"
   {:id "veterinary-supply" :name "獣医用品" :cost-threshold 500}

   "biosecurity-equipment"
   {:id "biosecurity-equipment" :name "バイオセキュリティ設備" :cost-threshold 1000}})

(defn supply-category-by-id [id]
  (get supply-categories id))

(def default-cost-threshold
  "Fallback escalation threshold used when a supply-order proposal doesn't
  cite a known category (never invent a lower bar than this)."
  500)

(def breeds
  "Common commercial swine breeds this actor's facility/herd records may
  cover (ISIC 0145: raising of swine/pigs)."
  {"landrace"  {:id "landrace"  :name "ランドレース"}
   "duroc"     {:id "duroc"     :name "デュロック"}
   "yorkshire" {:id "yorkshire" :name "ヨークシャー"}
   "berkshire" {:id "berkshire" :name "バークシャー"}})

(defn breed-by-id [id]
  (get breeds id))

(def biosecurity-concerns
  "Reference vocabulary for common swine biosecurity/notifiable-disease
  concerns this actor's :flag-animal-health-concern op may cite (e.g.
  suspected ASF on a neighboring premises, or a fresh mortality spike).
  Purely descriptive -- citing a concern (or leaving it free text) NEVER
  changes the Governor's disposition: EVERY flagged concern always
  escalates for veterinary/farm-operator review
  (`swineops.governor/always-escalate-ops`), regardless of the concern's
  `:notifiable` status or apparent severity. This actor has no authority
  to declare an outbreak, order a cull, or contact animal-health
  authorities -- it only surfaces the observation for human/veterinary
  judgment."
  {"asf"  {:id "asf"  :name "アフリカ豚熱 (African Swine Fever)" :notifiable true}
   "csf"  {:id "csf"  :name "豚熱 (Classical Swine Fever)" :notifiable true}
   "fmd"  {:id "fmd"  :name "口蹄疫 (Foot-and-Mouth Disease)" :notifiable true}
   "prrs" {:id "prrs" :name "豚繁殖・呼吸障害症候群 (PRRS)" :notifiable false}})

(defn biosecurity-concern-by-id [id]
  (get biosecurity-concerns id))
