export interface BrandInfo {
  name: string;
  aliases: string[];
  category: 'grocery' | 'marketplace' | 'food' | 'beauty' | 'electronics' | 'clothing' | 'pharmacy' | 'other';
  color?: string;
  icon?: string;
}

export const KNOWN_BRANDS: BrandInfo[] = [
  // Supermarkets & Grocery
  {
    name: 'Магнит',
    aliases: ['магнит', 'магнита', 'магните', 'магнит доставка', 'магнит аптека', 'магнит косметик'],
    category: 'grocery',
    color: '#E31E24',
  },
  {
    name: 'Пятёрочка',
    aliases: ['пятерочка', 'пятёрочка', 'пятерочки', 'пятёрочки', 'пятерочке', '5ка', 'пятерка', 'пятёрка'],
    category: 'grocery',
    color: '#1DA643',
  },
  {
    name: 'Перекрёсток',
    aliases: ['перекресток', 'перекрёсток', 'перекрестка', 'перекрестке', 'впрок'],
    category: 'grocery',
    color: '#005538',
  },
  {
    name: 'Лента',
    aliases: ['лента', 'ленты', 'ленте', 'лента онлайн'],
    category: 'grocery',
    color: '#003B95',
  },
  {
    name: 'ВкусВилл',
    aliases: ['вкусвилл', 'вкус вилл', 'вкусвилла', 'вкусвилле'],
    category: 'grocery',
    color: '#008744',
  },
  {
    name: 'Ашан',
    aliases: ['ашан', 'auchan', 'ашана', 'ашане'],
    category: 'grocery',
    color: '#E01A22',
  },
  {
    name: 'Метро',
    aliases: ['metro', 'метро', 'metro cash & carry', 'метро кэш энд керри'],
    category: 'grocery',
    color: '#002D72',
  },
  {
    name: 'Чижик',
    aliases: ['чижик', 'чижика', 'чижике'],
    category: 'grocery',
    color: '#FFCC00',
  },
  {
    name: 'Дикси',
    aliases: ['дикси'],
    category: 'grocery',
    color: '#FA6400',
  },

  // Food delivery & Fast Food
  {
    name: 'Самокат',
    aliases: ['самокат', 'самоката', 'самокате', 'samokat'],
    category: 'food',
    color: '#FF2E5B',
  },
  {
    name: 'Яндекс Лавка',
    aliases: ['яндекс лавка', 'яндекс.лавка', 'лавка', 'лавке', 'лавки'],
    category: 'food',
    color: '#FCE000',
  },
  {
    name: 'Яндекс Еда',
    aliases: ['яндекс еда', 'яндекс.еда', 'яндекс еды', 'еда'],
    category: 'food',
    color: '#FCE000',
  },
  {
    name: 'Купер',
    aliases: ['купер', 'купера', 'купере', 'сбермаркет', 'сбер маркет', 'kuper'],
    category: 'food',
    color: '#1DB954',
  },
  {
    name: 'Додо Пицца',
    aliases: ['додо пицца', 'додо', 'dodo pizza', 'dodo'],
    category: 'food',
    color: '#FF6900',
  },
  {
    name: 'Вкусно — и точка',
    aliases: ['вкусно и точка', 'вкусно — и точка', 'вкусно-и-точка', 'макдоналдс', 'мак'],
    category: 'food',
    color: '#E2231A',
  },
  {
    name: 'Бургер Кинг',
    aliases: ['бургер кинг', 'burger king', 'бк'],
    category: 'food',
    color: '#D62300',
  },
  {
    name: 'Rostic’s',
    aliases: ['rostics', 'rostic\'s', 'ростикс', 'ростик’с', 'kfc'],
    category: 'food',
    color: '#A3080C',
  },

  // Marketplaces
  {
    name: 'Ozon',
    aliases: ['ozon', 'озон', 'озона', 'озоне'],
    category: 'marketplace',
    color: '#005BFF',
  },
  {
    name: 'Wildberries',
    aliases: ['wildberries', 'вайлдберриз', 'вб', 'wb', 'вайлдбериз'],
    category: 'marketplace',
    color: '#CB11AB',
  },
  {
    name: 'Яндекс Маркет',
    aliases: ['яндекс маркет', 'яндекс.маркет', 'маркет', 'маркете'],
    category: 'marketplace',
    color: '#FC3F1D',
  },
  {
    name: 'Мегамаркет',
    aliases: ['мегамаркет', 'сбермегамаркет', 'мега маркет'],
    category: 'marketplace',
    color: '#34495E',
  },
  {
    name: 'AliExpress',
    aliases: ['aliexpress', 'алиэкспресс', 'али'],
    category: 'marketplace',
    color: '#FF4747',
  },
  {
    name: 'Авито',
    aliases: ['авито', 'avito'],
    category: 'marketplace',
    color: '#00AAFF',
  },

  // Cosmetics & Beauty
  {
    name: 'Золотое Яблоко',
    aliases: ['золотое яблоко', 'золотого яблока', 'золотом яблоке', 'зя', 'gold apple'],
    category: 'beauty',
    color: '#B89758',
  },
  {
    name: 'Л’Этуаль',
    aliases: ['лэтуаль', 'л’этуаль', 'летуаль', 'letu'],
    category: 'beauty',
    color: '#123992',
  },
  {
    name: 'Рив Гош',
    aliases: ['рив гош', 'ривгош', 'rive gauche'],
    category: 'beauty',
    color: '#000000',
  },

  // Pharmacy
  {
    name: 'ЕАПТЕКА',
    aliases: ['еаптека', 'е-аптека', 'eapteka', 'сбер еаптека'],
    category: 'pharmacy',
    color: '#00B0D9',
  },
  {
    name: 'Аптека Апрель',
    aliases: ['аптека апрель', 'апрель'],
    category: 'pharmacy',
    color: '#008752',
  },
  {
    name: 'Ригла',
    aliases: ['ригла', 'ригле'],
    category: 'pharmacy',
    color: '#008080',
  },

  // Electronics
  {
    name: 'DNS',
    aliases: ['dns', 'днс'],
    category: 'electronics',
    color: '#EC6608',
  },
  {
    name: 'М.Видео',
    aliases: ['м.видео', 'мвидео', 'м видео', 'mvideo'],
    category: 'electronics',
    color: '#ED1C24',
  },
  {
    name: 'Эльдорадо',
    aliases: ['эльдорадо', 'eldorado'],
    category: 'electronics',
    color: '#DA291C',
  },
  {
    name: 'Ситилинк',
    aliases: ['ситилинк', 'citilink'],
    category: 'electronics',
    color: '#FF6700',
  },
  {
    name: 're:Store',
    aliases: ['restore', 're:store', 'рестор'],
    category: 'electronics',
    color: '#1E1E1E',
  },

  // Fashion
  {
    name: 'Lamoda',
    aliases: ['lamoda', 'ламода', 'ламоде'],
    category: 'clothing',
    color: '#000000',
  },
  {
    name: 'Спортмастер',
    aliases: ['спортмастер', 'спортмастере'],
    category: 'clothing',
    color: '#005BBB',
  },
];
