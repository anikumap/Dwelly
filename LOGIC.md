# Dwelly — Logical Flow

Defines **how the system behaves**. Components are in `ARCHITECTURE.md`. Message numbers (1–18) are the same as in the Sequence, Collaboration and Activity diagrams. Do not add steps or features.

## 1. Definitions
- **Listing**: a property posted by a Landlord (`ownerId`, details, amenities, rules, photos, rent, deposit, maintenance, availability, lease terms, `status`).
- **Visible listing**: Listing with status ACTIVE. Seeded dummy listings are ACTIVE listings.
- **Own listing**: Listing whose `ownerId` is the caller.
- **Location**: place entered by a Tenant; can be changed at any time.
- **Tenant type**: RESIDENT (already lives in a rental) or SEEKER (looking), chosen at onboarding.
- **Agreement**: the rental agreement of one RENTED listing, between its Landlord and one Tenant. One per listing.
- **Thread**: forum conversation. One per listing, plus one general thread.
- **Landlord / Tenant / Admin** actions are allowed only for that role.

## 2. Listing state machine
```
(new) --createListing--> ACTIVE
ACTIVE --pause--> PAUSED --resume--> ACTIVE
ACTIVE / PAUSED --markRented--> RENTED
ACTIVE / PAUSED / RENTED --delete--> DELETED
```
| From | Event | Guard | Actions | To |
|---|---|---|---|---|
| (new) | createListing | Landlord | save listing; create thread | ACTIVE |
| ACTIVE | pause | caller owns listing | save | PAUSED |
| PAUSED | resume | caller owns listing | save | ACTIVE |
| ACTIVE / PAUSED | markRented | caller owns listing | save | RENTED |
| ACTIVE / PAUSED / RENTED | delete | caller owns listing | save | DELETED |
| any except DELETED | edit | caller owns listing | update fields | (same) |

Rejected requests change nothing.

## 3. Agreement state machine
`create` → **DRAFT** → `send` → **SENT** → `sign` (landlord and tenant) → **SIGNED**.
**Invariant:** SIGNED ⇔ both signatures recorded. An Agreement exists only for a RENTED listing, at most one per listing.

## 4. Flows

### 4.1 Create Listing (messages 1–6)
```
1  Landlord -> ListingService.createListing(details, photos, amenities, rules)
2  ok = caller.role == LANDLORD
3  if !ok: reply accessDenied; STOP
4  listing = Listing.save(ownerId, status = ACTIVE)
5  ForumService.createThread(listing.id)
6  reply listingCreated(listing)
```
Stats for the new listing start at views 0, saves 0, enquiries 0.

### 4.2 Discovery (messages 7–12)
```
7   Tenant -> DiscoveryService.getListings(sort)     # sort = LATEST | PRICE | NEAREST
8   loc = Tenant.location
9   if loc == null: reply promptLocation; STOP
10  listings = Listing.findActive()                  # seeded dummy listings included
11  ordered = order(listings, sort, loc)             # LATEST newest first · PRICE rent low→high or high→low · NEAREST distance ascending
12  reply listingFeed(ordered)
```
Discovery = read-only. Same for RESIDENT and SEEKER. A Landlord has no Discovery and cannot see other listings.

### 4.3 View Listing (messages 13–18)
```
13  Tenant -> ListingService.getListingDetail(listingId)
14  ok = Listing.isVisibleTo(tenantId)               # ACTIVE, or the RESIDENT's own rented property
15  if !ok: reply listingUnavailable; STOP
16  ListingStats.record(VIEW, listingId)             # views += 1
17  Tenant.addViewed(listingId)
18  reply listingDetail(listing)                     # everything the landlord provided
```
Save and enquire use the same check as step 14: `saveListing` → saves ±1; `sendEnquiry` → stores the enquiry for the Landlord, enquiries +1.

### 4.4 Manage Listing (Landlord only)
```
ListingService.updateListing(listingId, change)     # change = edit | pause | resume | markRented | delete
guard: caller owns listing AND transition allowed (section 2)
```

### 4.5 Rental Agreement (Landlord and Tenant)
```
createAgreement(listingId, tenantId)   # Landlord owns listing AND listing is RENTED AND no Agreement yet -> DRAFT, prefilled from listing
editAgreement(agreementId, clauses)    # Landlord, DRAFT only
sendAgreement(agreementId)             # Landlord, DRAFT AND all required clauses filled -> SENT
sign(agreementId)                      # Landlord or the named Tenant, SENT, not yet signed by caller; both signed -> SIGNED
```
Required clauses: parties, property, lease term, rent terms, deposit, maintenance and utilities, repair responsibilities, house rules, notice and termination, entry and inspection, inventory, signatures.
Both parties see the same document. Tenant sees it only when SENT or SIGNED. Tenant can download a copy.

### 4.6 Tenant Home
`getHome`: RESIDENT sees own property (the listing of an Agreement naming them, SENT or SIGNED) and nothing else; SEEKER sees listings already viewed (ACTIVE only, latest first) and popular listings.

### 4.7 Community Forum (Tenants only)
```
ForumService.getThreads()                    # general thread + one thread per ACTIVE listing
ForumService.post(threadId, text, replyTo?)  # Tenant
ForumService.react(postId, reaction)         # Tenant
ForumService.report(postId)                  # Tenant -> post flagged
ForumService.removePost(postId)              # Admin only
```
No landlord contact details appear in any thread.

### 4.8 Landlord Dashboard
`getMyListings`: Landlord sees only own listings (not DELETED), each with status, stats (views, saves, enquiries), completeness score and suggestions.

## 5. Statistics rules
`ListingStats.record(action, listingId)` inserts a log row, then increments the counter for that listing:
| Action | Effect on stats |
|---|---|
| VIEW | views +1 |
| SAVE | saves +1 (unsave: saves −1) |
| ENQUIRY | enquiries +1 |

**Popular** = ACTIVE listings ordered by `views + saves + enquiries`, highest first.
**Completeness score** = filled items ÷ total items × 100 (description, photos, property details, price fields, amenities, rules); each unfilled item gives one suggestion.

## 6. Error replies (only these)
| Case | Reply |
|---|---|
| Wrong role, not the owner, or not a party to the Agreement | accessDenied |
| Tenant has no Location | promptLocation |
| Listing not ACTIVE (and not the RESIDENT's own property) | listingUnavailable |
| Listing change not allowed from current status | rejectChange(reason) |
| Agreement action not allowed | rejectAgreement(reason) |

## 7. Not specified (leave out)
Online rent payment, chat between landlord and tenant, notifications, map view, search radius, listing approval, agreement renewal, agreement edits after sending, forum behaviour when a listing is deleted.
