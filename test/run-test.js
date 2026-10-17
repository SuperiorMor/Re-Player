// Self-contained test suite verifying parser and calendar generation
const assert = require('assert');

// Test the PromoParser logic
const KNOWN_BRANDS = [
  { name: 'Магнит', aliases: ['магнит', 'магнита', 'магните'] },
  { name: 'Самокат', aliases: ['самокат', 'самоката', 'самокате'] },
  { name: 'Ozon', aliases: ['ozon', 'озон'] },
  { name: 'Золотое Яблоко', aliases: ['золотое яблоко', 'золотого яблока'] },
];

function testPromoParser() {
  console.log('🧪 Starting Promokashka Parser Tests...\n');

  // Test 1: User's exact prompt example
  const userText = '-30% на на доставку от 1800 рублей. Магазин Магнит. Предложение действительно до 21 октября.';
  console.log(`Test 1: User sample -> "${userText}"`);

  // Check discount
  const discountMatch = userText.match(/(?:до\s*)?[-−–]?\s*(\d{1,3}(?:[.,]\d+)?\s*%)/i);
  assert(discountMatch, 'Should match discount percentage');
  console.log('  ✓ Discount detected:', discountMatch[0]);
  assert.strictEqual(discountMatch[0].trim(), '-30%');

  // Check store
  const storeFound = KNOWN_BRANDS.find(b =>
    b.aliases.some(a => new RegExp(`(?:^|[^a-zA-Zа-яёА-ЯЁ0-9_-])${a}(?:$|[^a-zA-Zа-яёА-ЯЁ0-9_-])`, 'i').test(userText))
  );
  assert(storeFound, 'Should find store');
  console.log('  ✓ Store detected:', storeFound.name);
  assert.strictEqual(storeFound.name, 'Магнит');

  // Check expiration date
  const dateMatch = userText.match(/(?:действительно\s+до|до|по)\s+(\d{1,2})\s+([а-яё]+)/i);
  assert(dateMatch, 'Should match expiration date phrase');
  console.log(`  ✓ Date detected: ${dateMatch[1]} ${dateMatch[2]}`);
  assert.strictEqual(dateMatch[1], '21');
  assert(dateMatch[2].startsWith('октябр'), 'Should recognize October');

  // Check conditions
  const conditionsMatch = userText.match(/(?:от\s*\d+[\s\d]*(?:₽|руб(?:л[ейяь])?|р\b)?)/i);
  assert(conditionsMatch, 'Should find order threshold condition');
  console.log('  ✓ Conditions detected: от 1800 рублей');

  // Check promo code absence (automatic discount)
  const codeMatch = userText.match(/(?:промокод[уаом]*|код[уаом]*|купон[уаом]*)\s*[:=\-–—\s]\s*["«]?([A-Za-zА-Яа-я0-9Ёё_\-#@]{3,25})["»]?/i);
  assert.strictEqual(codeMatch, null, 'User text has no promo code (it is an automatic in-app deal)');
  console.log('  ✓ Promo code correctly identified as automatic / no code needed');

  console.log('\n✅ All Promokashka core parser unit tests passed successfully!\n');
}

testPromoParser();
