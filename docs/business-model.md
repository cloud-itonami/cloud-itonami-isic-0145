# Business Model: Swine-Farm Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0144`
- ISIC Rev. 4: `0144`
- Industry: Raising of swine/pigs
- Social impact: animal-welfare, food-security, rural-employment

## Customer

- Small-to-medium swine farms (farrow-to-finish, nursery, grow-finish operations)
- Breeding-stock operations
- Contract/independent pork producers
- Cooperative and integrator-affiliated swine operations

## Offer

- Herd management and record-keeping, including farrowing data
- Veterinary appointment coordination
- Health and biosecurity tracking (e.g. ASF risk surfacing)
- Supply procurement coordination
- Audit trail and transparency

## Revenue

- SaaS subscription (per-head-per-month pricing)
- Supply chain integration fees
- API access for veterinary partners
- Data analytics and reporting add-ons

## Trust Controls

- No slaughter or culling decisions without human sign-off
- No direct treatment administration
- All veterinary recommendations are proposals, not commands
- Facility (barn/pen) registration is required before any operation
- All animal health/biosecurity concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we do NOT do

- **Veterinary treatment decisions** — the veterinarian decides treatment
- **Animal welfare decisions** — the farm operator decides welfare actions
- **Economic decisions** (slaughter, culling, breeding) — remain human authority
- **Direct animal handling** — the robot manages records and logistics only
- **Outbreak declarations / animal-health authority contact** — flagged
  concerns (e.g. suspected ASF) are surfaced for human/veterinary judgment
  only

## Supported Operations

### Herd Record Logging
- Daily herd counts
- Weight tracking
- Health status notes
- Farrowing data (litter counts, birth/death records — logging only, not decision-making)

### Veterinary Coordination
- Schedule vet visits
- Track vet exam results
- Propose follow-up care (not order it directly)

### Health/Biosecurity Concern Escalation
- Flag suspected disease (e.g. African Swine Fever, Classical Swine Fever,
  Foot-and-Mouth Disease, PRRS)
- Report injuries or welfare concerns
- Automatic escalation to farm operator/veterinarian

### Supply Procurement
- Feed orders
- Veterinary supply orders
- Biosecurity-equipment procurement (PPE, disinfectant, perimeter controls)
- Cost threshold escalation for large orders
