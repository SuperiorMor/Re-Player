export interface ParsedPromo {
  id: string;
  originalText: string;
  store: string;
  discount: string;
  promoCode: string | null;
  expirationDate: string | null; // ISO format or YYYY-MM-DD
  expirationDateDisplay: string;
  daysRemaining: number | null;
  isExpired: boolean;
  conditions: string;
  confidenceScore: number;
  addedToCalendar: boolean;
  calendarEventId?: string;
  createdAt: string;
}

export interface PromoParseResult {
  promo: ParsedPromo;
  extractedFields: {
    store: { value: string; match: string | null; confidence: number };
    discount: { value: string; match: string | null; confidence: number };
    promoCode: { value: string | null; match: string | null; confidence: number };
    expirationDate: { value: string | null; match: string | null; confidence: number };
    conditions: { value: string; match: string | null; confidence: number };
  };
}

export type PromoStatus = 'all' | 'active' | 'expiring' | 'expired';
