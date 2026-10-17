import { PromoParser } from '../src/services/promoParser.ts';

const testCases = [
  {
    name: "User Example",
    text: "-30% на на доставку от 1800 рублей. Магазин Магнит. Предложение действительно до 21 октября.",
  },
  {
    name: "Samokat with promo code",
    text: "Скидка 300 ₽ на заказ от 1200 ₽ по промокоду ОСЕНЬ300 в приложении Самокат! Успейте применить до 25 октября включительно.",
  },
  {
    name: "Ozon Marketplace",
    text: "Дарим 500 рублей на первую покупку от 2500 рублей в маркетплейсе Ozon. Промокод: OZONFIRST. Акция действует до 31 октября 2026 г.",
  },
  {
    name: "Gold Apple cosmetics",
    text: "Золотое Яблоко: -20% на категорию парфюмерии и ухода от 3000 руб. Код: BEAUTY20. Срок действия до 28 октября.",
  },
  {
    name: "Free Delivery relative date",
    text: "Бесплатная доставка и скидка 15% на первый заказ от 1000 рублей в сервисе Яндекс Лавка. Предложение действительно до конца недели.",
  }
];

console.log("=== RUNNING PROMO PARSER TESTS ===\n");

for (const tc of testCases) {
  console.log(`\n--- Test: ${tc.name} ---`);
  console.log(`Text: "${tc.text}"`);
  const result = PromoParser.parse(tc.text);
  console.log("Result:", JSON.stringify({
    store: result.promo.store,
    discount: result.promo.discount,
    promoCode: result.promo.promoCode,
    expirationDateDisplay: result.promo.expirationDateDisplay,
    daysRemaining: result.promo.daysRemaining,
    conditions: result.promo.conditions,
    confidenceScore: result.promo.confidenceScore
  }, null, 2));
}
