// Voidburn tests for shops.test.cjs, appended to the upstream file only while the
// updater runs the Voidburn pass (titles start "Voidburn: "). Upstream's file stays pristine.
function fixedStockHarness(t) {
  const handlers = new Map();
  require('../plugins/world/StaticShopStock.plugin').register({
    onCustomEvent: (name, handler) => handlers.set(name, handler),
  });
  const emit = PluginManager.emitCustomEvent.bind(PluginManager);
  t.mock.method(PluginManager, 'emitCustomEvent', (name, request) => {
    emit(name, request);
    handlers.get(name)?.(request);
  });
}

test('Voidburn: fixed shop stock survives repeated purchases and sales at the same prices', (t) => {
  fixedStockHarness(t);
  const player = openShop();
  const original = stockOf();
  for (let cycle = 0; cycle < 3; cycle++) {
    const balance = player.getInventory().getAmount(COINS);
    buy(player, 1, STEEL_AXE);
    buy(player, 1, STEEL_AXE);
    assert.equal(player.getInventory().getAmount(STEEL_AXE), 2);
    assert.equal(player.getInventory().getAmount(COINS), balance - 400);
    assert.deepEqual(stockOf(), original);
    const slot = player.getInventory().getItems().findIndex(item => item?.getId() === STEEL_AXE);
    sell(player, slot, 'Sell 5');
    assert.equal(player.getInventory().getAmount(STEEL_AXE), 0);
    assert.equal(player.getInventory().getAmount(COINS), balance - 60);
    assert.deepEqual(stockOf(), original);
    assert.equal(ShopManager.restockAll(), false);
  }
});

test('Voidburn: fixed stock is available to other players and still requires payment', (t) => {
  fixedStockHarness(t);
  const a = openShop();
  buy(a, 1, STEEL_AXE);
  const b = createPlayer();
  b.getInventory().add(new Item(COINS, 200), false);
  ShopManager.open(b, AXE_SHOP);
  buy(b, 1, STEEL_AXE);
  assert.equal(b.getInventory().getAmount(STEEL_AXE), 1);
  assert.equal(b.getInventory().getAmount(COINS), 0);
  buy(b, 1, STEEL_AXE);
  assert.equal(b.getInventory().getAmount(STEEL_AXE), 1);
  assert.deepEqual(stockOf(), [[BRONZE_AXE, 2], [STEEL_AXE, 1]]);
});

test('Voidburn: fixed general stores pay for sales without accumulating new or existing items', (t) => {
  fixedStockHarness(t);
  const player = openShop(0, GENERAL_STORE);
  player.getInventory().add(new Item(STEEL_AXE, 1), false);
  player.getInventory().add(new Item(BRONZE_AXE, 1), false);
  sell(player, 0);
  assert.equal(player.getInventory().getAmount(STEEL_AXE), 0);
  assert.equal(player.getInventory().getAmount(COINS), 170);
  const slot = player.getInventory().getItems().findIndex(item => item?.getId() === BRONZE_AXE);
  sell(player, slot);
  assert.equal(player.getInventory().getAmount(BRONZE_AXE), 0);
  assert.equal(player.getInventory().getAmount(COINS), 183);
  assert.deepEqual(stockOf(GENERAL_STORE), [[BRONZE_AXE, 3]]);
});

test('Voidburn: fixed stock leaves configured zero-stock items unavailable', (t) => {
  fixedStockHarness(t);
  loadShops(0);
  const player = createPlayer();
  player.getInventory().add(new Item(COINS, 1000), false);
  ShopManager.open(player, AXE_SHOP);
  buy(player, 1, STEEL_AXE);
  assert.equal(player.getInventory().getAmount(STEEL_AXE), 0);
  assert.equal(player.getInventory().getAmount(COINS), 1000);
  assert.deepEqual(stockOf(), [[BRONZE_AXE, 2], [STEEL_AXE, 0]]);
});
