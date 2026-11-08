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
const UNIQUE_ACTIVITIES = new Set([
  "hallowed-sepulchre", "pyramid-plunder", "fishing-trawler", "gnome-restaurant",
  "aerial-fishing", "tai-bwo-wannai-cleanup",
]);

// Explicit reward rates can differ from the corresponding skill XP rate.
const ACTIVITY_REWARD_MULTIPLIERS = Object.freeze({ "motherlode-mine": 2, "mage-training-arena": 2 });

function multiplyEarnings(request) {
  const skill = ACTIVITY_SKILLS[request.minigame];
  if (!skill || !(request.points > 0) || !Number.isFinite(request.points)) return;
  request.points = Math.min(2147483647, Math.floor(request.points * (ACTIVITY_REWARD_MULTIPLIERS[request.minigame] ?? rates[skill] ?? 1)));
}

function multiplyUniqueChance(request) {
  if (request.kind !== "unique" || CREDIT_ACTIVITIES.has(request.minigame) || !UNIQUE_ACTIVITIES.has(request.minigame)) return;
  request.multiplier *= 2;
}

module.exports = {
  name: "RevenantMinigameRewards",
  register(api) {
    api.onCustomEvent("minigame:points", multiplyEarnings);
    api.onCustomEvent("minigame:chance", multiplyUniqueChance);
  },
  _test: { ACTIVITY_SKILLS, CREDIT_ACTIVITIES, UNIQUE_ACTIVITIES },
};
