function doubleTaskPoints(reward) {
  reward.multiplier *= 2;
}

module.exports = {
  name: "VoidburnSlayerRewards",
  description: "Doubles Slayer task reward points.",
  register(api) {
    api.onCustomEvent("slayer:task-reward", doubleTaskPoints);
  },
};
