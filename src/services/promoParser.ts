import { KNOWN_BRANDS } from '../constants/brands';
import { ParsedPromo, PromoParseResult } from '../types/promo';

const MONTHS_RU: { [key: string]: number } = {
  январ: 0,
  феврал: 1,
  март: 2,
  апрел: 3,
  ма: 4,
  июн: 5,
  июл: 6,
  август: 7,
  сентябр: 8,
  октябр: 9,
  ноябр: 10,
  декабр: 11,
};

const MONTH_NAMES_RU = [
  'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря',
];

export class PromoParser {
  /**
   * Main entry point to parse unstructured promo text into structured entity
   */
  public static parse(rawText: string): PromoParseResult {
    const text = (rawText || '').trim();

    const discountResult = this.extractDiscount(text);
    const storeResult = this.extractStore(text);
    const promoCodeResult = this.extractPromoCode(text, storeResult.value);
    const expirationResult = this.extractExpirationDate(text);
    const conditionsResult = this.extractConditions(text);

    // Calculate remaining days & expiry status
    let daysRemaining: number | null = null;
    let isExpired = false;
    let expirationDateDisplay = 'Бессрочно / Не указана';

    if (expirationResult.value) {
      const expDate = new Date(expirationResult.value);
      if (!isNaN(expDate.getTime())) {
        const now = new Date();
        const diffMs = expDate.getTime() - now.getTime();
        daysRemaining = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
        isExpired = daysRemaining < 0;

        const day = expDate.getDate();
        const monthName = MONTH_NAMES_RU[expDate.getMonth()];
        const year = expDate.getFullYear();
        expirationDateDisplay = `${day} ${monthName} ${year}`;
      }
    }

    // Calculate an overall extraction confidence score (0 - 100)
    let score = 0;
    if (discountResult.confidence > 0) score += 30;
    if (storeResult.confidence > 0) score += 25;
    if (expirationResult.confidence > 0) score += 25;
    if (promoCodeResult.confidence > 0) score += 10;
    if (conditionsResult.confidence > 0) score += 10;

    const promo: ParsedPromo = {
      id: 'promo_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7),
      originalText: text,
      store: storeResult.value || 'Магазин не указан',
      discount: discountResult.value || 'Скидка',
      promoCode: promoCodeResult.value,
      expirationDate: expirationResult.value,
      expirationDateDisplay,
      daysRemaining,
      isExpired,
      conditions: conditionsResult.value || 'Условия не уточнены',
      confidenceScore: Math.min(100, Math.max(0, score)),
      addedToCalendar: false,
      createdAt: new Date().toISOString(),
    };

    return {
      promo,
      extractedFields: {
        store: storeResult,
        discount: discountResult,
        promoCode: promoCodeResult,
        expirationDate: expirationResult,
        conditions: conditionsResult,
      },
    };
  }

  /**
   * Extract discount size (percentage, cash discount, combo, free delivery)
   */
  private static extractDiscount(text: string): { value: string; match: string | null; confidence: number } {
    // 1. Percentage discount: -30%, 30%, до -50%
    const percentRegex = /(?:до\s*)?[-−–]?\s*(\d{1,3}(?:[.,]\d+)?\s*%)/i;
    const percentMatch = text.match(percentRegex);
    if (percentMatch) {
      const isMinus = /[-−–]/.test(text.slice(Math.max(0, percentMatch.index! - 2), percentMatch.index! + 3));
      const value = (isMinus && !percentMatch[0].startsWith('-') ? '-' : '') + percentMatch[1].replace(/\s+/, '');
      return { value, match: percentMatch[0], confidence: 0.95 };
    }

    // 2. Fixed amount discount in rubles: 500 ₽, 1000 руб, 300 рублей, -500р
    const rublesRegex = /(?:скидк[аеи]|минус|выгод[аеы]|бонус[аов]*|дарим)?\s*[-−–]?\s*(\d+[\s\d]*)\s*(?:₽|руб(?:л[ейяь])?|р\b)/i;
    const rublesMatch = text.match(rublesRegex);
    if (rublesMatch) {
      const num = rublesMatch[1].replace(/\s+/g, '');
      return { value: `${num} ₽`, match: rublesMatch[0], confidence: 0.9 };
    }

    // 3. Free delivery
    const freeDeliveryRegex = /(?:бесплатн(?:ая|ую|ой|о)?\s+доставк[аеу]|доставка\s+0\s*(?:₽|р|руб))/i;
    const freeDeliveryMatch = text.match(freeDeliveryRegex);
    if (freeDeliveryMatch) {
      return { value: 'Бесплатная доставка', match: freeDeliveryMatch[0], confidence: 0.9 };
    }

    // 4. Buy X Get Y / Combo (1+1, 2=1)
    const comboRegex = /(?:1\s*\+\s*1|2\s*по\s*цене\s*1|2\s*=\s*1|3\s*по\s*цене\s*2|подарок\s+к\s+заказу)/i;
    const comboMatch = text.match(comboRegex);
    if (comboMatch) {
      return { value: comboMatch[0].trim(), match: comboMatch[0], confidence: 0.85 };
    }

    // 5. Cashback
    const cashbackRegex = /(?:к[еэ]шб[еэ]к|кешбек)\s*(\d+%\b|\d+\s*(?:₽|руб))/i;
    const cashbackMatch = text.match(cashbackRegex);
    if (cashbackMatch) {
      return { value: `Кешбэк ${cashbackMatch[1]}`, match: cashbackMatch[0], confidence: 0.85 };
    }

    return { value: 'Скидка', match: null, confidence: 0.1 };
  }

  /**
   * Extract store/service name
   */
  private static extractStore(text: string): { value: string; match: string | null; confidence: number } {
    // 1. Check known brands catalog
    const lower = text.toLowerCase();
    for (const brand of KNOWN_BRANDS) {
      for (const alias of brand.aliases) {
        // Word boundary match
        const regex = new RegExp(`(?:^|[^a-zA-Zа-яёА-ЯЁ0-9_-])${alias}(?:$|[^a-zA-Zа-яёА-ЯЁ0-9_-])`, 'i');
        const match = lower.match(regex);
        if (match) {
          return { value: brand.name, match: alias, confidence: 0.95 };
        }
      }
    }

    // 2. Explicit store prefixes: "Магазин Магнит", "сервис Самокат", "в приложении Ozon"
    const prefixRegex = /(?:магазин[ае]?|супермаркет[ае]?|гипермаркет[ае]?|сервис[ае]?|маркетплейс[ае]?|сеть|аптек[аеи]|ресторан[ае]?)\s+["«]?([A-Za-zА-Яа-я0-9Ёё\s\-_&]{2,25})["»]?/i;
    const prefixMatch = text.match(prefixRegex);
    if (prefixMatch && prefixMatch[1]) {
      const storeCandidate = prefixMatch[1].trim();
      // Filter out generic phrases
      if (!['доставки', 'онлайн', 'товаров', 'акций'].includes(storeCandidate.toLowerCase())) {
        return { value: storeCandidate, match: prefixMatch[0], confidence: 0.85 };
      }
    }

    // 3. Quoted merchant name: в «Магните», заказ в "Ленте"
    const quotedRegex = /(?:в|для|от)\s+["«]([A-Za-zА-Яа-я0-9Ёё\s\-_]{2,20})["»]/i;
    const quotedMatch = text.match(quotedRegex);
    if (quotedMatch && quotedMatch[1]) {
      return { value: quotedMatch[1].trim(), match: quotedMatch[0], confidence: 0.75 };
    }

    return { value: 'Магазин', match: null, confidence: 0.2 };
  }

  /**
   * Extract promo code if available
   */
  private static extractPromoCode(text: string, storeName?: string): { value: string | null; match: string | null; confidence: number } {
    // 1. Explicit keyword: "промокод: SALE30", "по промокоду ОСЕНЬ300", "код BEAUTY20", "купон START"
    const keywordRegex = /(?:промокод[уаом]*|код[уаом]*|купон[уаом]*|code)\s*(?:на\s+скидку\s*)?[:=\-–—\s]\s*["«]?([A-Za-zА-Яа-я0-9Ёё_\-#@]{3,25})["»]?/i;
    const keywordMatch = text.match(keywordRegex);
    if (keywordMatch && keywordMatch[1]) {
      const code = keywordMatch[1].trim().replace(/[.,!?;:]+$/, '');
      // Ensure it's not a common stop word like "на", "для", "от"
      const stopWords = ['на', 'для', 'от', 'при', 'все', 'всех', 'скидка', 'доставка', 'рублей', 'руб'];
      if (!stopWords.includes(code.toLowerCase())) {
        return { value: code.toUpperCase(), match: keywordMatch[0], confidence: 0.95 };
      }
    }

    // 2. Quoted promo code: промокод "ОСЕНЬ300"
    const quotedRegex = /(?:промокод|код|купон)[^.\n]*?["«]([A-Za-zА-Яа-я0-9Ёё_\-#@]{3,25})["»]/i;
    const quotedMatch = text.match(quotedRegex);
    if (quotedMatch && quotedMatch[1]) {
      return { value: quotedMatch[1].trim().toUpperCase(), match: quotedMatch[0], confidence: 0.9 };
    }

    // 3. Standalone code token heuristic (all caps word with numbers or Latin caps, e.g. "SALE30", "SAVE500", "MAGNIT30")
    const words = text.split(/[\s,.;:!?()«»"']+/);
    for (const word of words) {
      if (
        word.length >= 4 &&
        word.length <= 15 &&
        /^[A-Z0-9_-]+$/.test(word) &&
        /[0-9]/.test(word) &&
        /[A-Z]/.test(word) &&
        word !== storeName?.toUpperCase()
      ) {
        return { value: word, match: word, confidence: 0.7 };
      }
    }

    return { value: null, match: null, confidence: 0 };
  }

  /**
   * Extract expiration date and convert to ISO string Date
   */
  private static extractExpirationDate(text: string): { value: string | null; match: string | null; confidence: number } {
    const now = new Date();
    const currentYear = now.getFullYear();

    // 1. Textual Russian month: "до 21 октября", "действительно до 21 октября", "до 21 октября 2026", "по 25 октября включительно"
    const textMonthRegex = /(?:действительно\s+до|действует\s+до|до|по|срок\s+действия\s*до|истекает|заканчивается)\s+(\d{1,2})\s+([а-яё]+)(?:\s+(\d{4}))?/i;
    const textMatch = text.match(textMonthRegex);
    if (textMatch) {
      const day = parseInt(textMatch[1], 10);
      const rawMonth = textMatch[2].toLowerCase();
      let matchedMonth: number | null = null;

      for (const [key, mIndex] of Object.entries(MONTHS_RU)) {
        if (rawMonth.startsWith(key)) {
          matchedMonth = mIndex;
          break;
        }
      }

      if (matchedMonth !== null && day >= 1 && day <= 31) {
        let year = textMatch[3] ? parseInt(textMatch[3], 10) : currentYear;

        // If the date passed already in current year and no year specified, assume next year
        const candidate = new Date(year, matchedMonth, day, 23, 59, 59);
        if (!textMatch[3] && candidate.getTime() < now.getTime() - 24 * 60 * 60 * 1000) {
          candidate.setFullYear(currentYear + 1);
        }

        return {
          value: candidate.toISOString(),
          match: textMatch[0],
          confidence: 0.95,
        };
      }
    }

    // 2. Numerical format: "до 21.10.2026", "до 21.10", "до 21/10/2026"
    const numericRegex = /(?:до|по|действует\s+до)\s+(\d{1,2})[./\-](\d{1,2})(?:[./\-](\d{2,4}))?/i;
    const numMatch = text.match(numericRegex);
    if (numMatch) {
      const day = parseInt(numMatch[1], 10);
      const month = parseInt(numMatch[2], 10) - 1;
      let year = numMatch[3] ? parseInt(numMatch[3], 10) : currentYear;
      if (year < 100) year += 2000;

      if (month >= 0 && month <= 11 && day >= 1 && day <= 31) {
        const candidate = new Date(year, month, day, 23, 59, 59);
        return {
          value: candidate.toISOString(),
          match: numMatch[0],
          confidence: 0.9,
        };
      }
    }

    // 3. Relative dates: "до конца недели", "до конца месяца", "до завтра", "только сегодня"
    if (/до\s+конца\s+недели/i.test(text)) {
      const endOfWeek = new Date(now);
      const day = now.getDay();
      const diff = 7 - (day === 0 ? 7 : day); // Days until Sunday
      endOfWeek.setDate(now.getDate() + diff);
      endOfWeek.setHours(23, 59, 59, 0);
      return { value: endOfWeek.toISOString(), match: 'до конца недели', confidence: 0.85 };
    }

    if (/до\s+конца\s+месяца/i.test(text)) {
      const endOfMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0, 23, 59, 59);
      return { value: endOfMonth.toISOString(), match: 'до конца месяца', confidence: 0.85 };
    }

    if (/до\s+завтра/i.test(text)) {
      const tomorrow = new Date(now);
      tomorrow.setDate(now.getDate() + 1);
      tomorrow.setHours(23, 59, 59, 0);
      return { value: tomorrow.toISOString(), match: 'до завтра', confidence: 0.85 };
    }

    if (/только\s+сегодня/i.test(text)) {
      const today = new Date(now);
      today.setHours(23, 59, 59, 0);
      return { value: today.toISOString(), match: 'только сегодня', confidence: 0.85 };
    }

    return { value: null, match: null, confidence: 0 };
  }

  /**
   * Extract conditions (e.g. "на доставку от 1800 рублей", "на первый заказ", "в приложении")
   */
  private static extractConditions(text: string): { value: string; match: string | null; confidence: number } {
    const conditionsFound: string[] = [];

    // Order threshold: "от 1800 рублей", "на доставку от 1800 рублей", "при заказе от 2000 ₽"
    const orderMinRegex = /(?:на\s+(?:доставку|заказ|покупку)\s+)?(?:при\s+заказ[еи]\s+|при\s+покупк[еи]\s+)?(?:от|на\s+сумму\s+от)\s*(\d+[\s\d]*)\s*(?:₽|руб(?:л[ейяь])?|р\b)/i;
    const orderMinMatch = text.match(orderMinRegex);
    if (orderMinMatch) {
      conditionsFound.push(`Заказ от ${orderMinMatch[1].replace(/\s+/g, '')} ₽`);
    }

    // Target audience: first order / repeat order
    if (/на\s+перв(?:ый|ом)\s+заказ/i.test(text)) {
      conditionsFound.push('На первый заказ');
    } else if (/на\s+повторн(?:ый|ые)\s+заказ/i.test(text)) {
      conditionsFound.push('На повторный заказ');
    }

    // Delivery specific
    if (/на\s+(?:самовывоз)/i.test(text)) {
      conditionsFound.push('Самовывоз');
    } else if (/на\s+доставку/i.test(text) && !conditionsFound.some(c => c.includes('Заказ от'))) {
      conditionsFound.push('На доставку');
    }

    // App only
    if (/только\s+в\s+приложени[еи]|в\s+мобильном\s+приложени[еи]/i.test(text)) {
      conditionsFound.push('Только в мобильном приложении');
    }

    // Category
    const categoryRegex = /(?:на\s+товары\s+категории|на\s+категори[юи]|на\s+раздел)\s+([^.,;!?\n]+)/i;
    const catMatch = text.match(categoryRegex);
    if (catMatch && catMatch[1]) {
      conditionsFound.push(`Категория: ${catMatch[1].trim()}`);
    }

    if (conditionsFound.length > 0) {
      return {
        value: conditionsFound.join(' • '),
        match: conditionsFound.join(', '),
        confidence: 0.9,
      };
    }

    return { value: 'Без специальных ограничений', match: null, confidence: 0.2 };
  }
}
