// Deployment-specific skill rates. Unlisted skills retain the world's base rate.
const rates = Object.freeze(require("./data/revenant-skill-rates.json"));

function applySkillRate(event) {
  event.multiplier *= rates[event.skill.getName()] ?? 1;
}

module.exports = {
  name: "RevenantSkillRates",
  register(api) {
    api.onCanGainExperience(applySkillRate);
  },
};
