// The Mining plugin continues its existing session when depletion is declined.
// Mining success rates, pickaxe intervals, rewards and interruption checks stay native.
function keepRockAvailable(event) {
  event.handled = true;
}

module.exports = {
  name: "PersistentMining",
  register(api) {
    api.onCustomEvent("mining:deplete-rock", keepRockAvailable);
  },
};
