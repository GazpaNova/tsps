/** Keep every shop's configured stock unchanged by purchases and sales. */
function preserveStock(request) {
  request.preserveStock = true;
}

module.exports = {
  name: "StaticShopStock",
  register(api) {
    api.onCustomEvent("shop:stock-policy", preserveStock);
  },
};
