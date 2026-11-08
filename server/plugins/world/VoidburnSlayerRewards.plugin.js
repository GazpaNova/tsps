function doubleTaskPoints(reward) {
  reward.multiplier *= 2;
}

module.exports = {
  name: "VoidburnSlayerRewards",
  register(api) {
    api.onCustomEvent("slayer:task-reward", doubleTaskPoints);
  },
};
