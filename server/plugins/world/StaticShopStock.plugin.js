/** Keep every shop's configured stock unchanged by purchases and sales. */
function preserveStock(request) {
  request.preserveStock = true;
}

module.exports = {
  name: "StaticShopStock",
  description: "Keeps shop stock and prices fixed during trades.",
  register(api) {
    api.onCustomEvent("shop:stock-policy", preserveStock);
  },
};
