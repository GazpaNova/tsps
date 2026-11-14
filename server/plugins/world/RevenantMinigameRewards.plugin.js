// Reward earnings follow the same skill rates as XP; spending and refunds are untouched.
const rates = require("./data/revenant-skill-rates.json");
const ACTIVITY_SKILLS = Object.freeze({
  "rooftop-agility": "Agility", "brimhaven-agility-arena": "Agility", "hallowed-sepulchre": "Agility",
  "motherlode-mine": "Mining", "volcanic-mine": "Mining", "shooting-stars": "Mining",
  "forestry": "Woodcutting", "tai-bwo-wannai-cleanup": "Woodcutting",
  "guardians-of-the-rift": "Runecrafting",
  "giants-foundry": "Smithing", "vale-totems": "Fletching", "vale-research": "Fletching",
  "mahogany-homes": "Construction", "mastering-mixology": "Herblore", "tithe-farm": "Farming",
  "tempoross": "Fishing", "aerial-fishing": "Fishing", "hunters-rumours": "Hunter",
  "wintertodt": "Firemaking", "shades-of-mortton": "Firemaking", "gnome-restaurant": "Cooking",
  "mage-training-arena": "Magic",
});
// GOTR pearls keep their base quantities; Runecrafting scales search credits only.
// Extra reward searches replace the 2x per-roll unique boost, never stack with it.
const CREDIT_ACTIVITIES = new Set([
  "guardians-of-the-rift", "tempoross", "wintertodt", "vale-totems", "shades-of-mortton", "hunters-rumours",
]);
// Upstream names each rare find ("minigame:chance" kind, RSPSApp/tsps#5245); "unique" is the
// Voidburn-only name used before that, kept so either version of the minigames is boosted.
const UNIQUE_KINDS = Object.freeze({
  "hallowed-sepulchre": ["strange-lockpick", "ring-of-endurance"],
  "pyramid-plunder": ["pharaohs-sceptre"],
  "fishing-trawler": ["angler-outfit"],
  "gnome-restaurant": ["unique"],
  "aerial-fishing": ["golden-tench"],
  "tai-bwo-wannai-cleanup": ["gout-tuber"],
});
const UNIQUE_ACTIVITIES = new Set(Object.keys(UNIQUE_KINDS));

// Explicit reward rates can differ from the corresponding skill XP rate. Last Man Standing has no
// skill: its earned points are x4 (ranks and the shop's prices are unchanged).
const ACTIVITY_REWARD_MULTIPLIERS = Object.freeze({ "motherlode-mine": 2, "mage-training-arena": 2, "last-man-standing": 4 });

function multiplyEarnings(request) {
  const skill = ACTIVITY_SKILLS[request.minigame];
  const multiplier = ACTIVITY_REWARD_MULTIPLIERS[request.minigame] ?? (skill ? rates[skill] ?? 1 : undefined);
  if (multiplier === undefined || !(request.points > 0) || !Number.isFinite(request.points)) return;
  request.points = Math.min(2147483647, Math.floor(request.points * multiplier));
}

function multiplyUniqueChance(request) {
  if (CREDIT_ACTIVITIES.has(request.minigame) || !UNIQUE_ACTIVITIES.has(request.minigame)) return;
  if (request.kind !== "unique" && !UNIQUE_KINDS[request.minigame].includes(request.kind)) return;
  request.multiplier *= 2;
}

module.exports = {
  name: "RevenantMinigameRewards",
  description: "Applies Voidburn reward multipliers to skilling minigames.",
  register(api) {
    api.onCustomEvent("minigame:points", multiplyEarnings);
    api.onCustomEvent("minigame:chance", multiplyUniqueChance);
  },
  _test: { ACTIVITY_SKILLS, CREDIT_ACTIVITIES, UNIQUE_ACTIVITIES, UNIQUE_KINDS },
};
