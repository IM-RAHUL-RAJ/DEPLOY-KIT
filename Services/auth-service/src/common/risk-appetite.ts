/** Investment risk bands a registrant declares, used to seed their profile. */
export enum RiskAppetite {
  CONSERVATIVE = 'CONSERVATIVE',
  BALANCED = 'BALANCED',
  AGGRESSIVE = 'AGGRESSIVE',
}

export const RISK_APPETITES: readonly RiskAppetite[] = [
  RiskAppetite.CONSERVATIVE,
  RiskAppetite.BALANCED,
  RiskAppetite.AGGRESSIVE,
];

/** Lifecycle a user and their account move through, in order. */
export enum UserStatus {
  INACTIVE = 'INACTIVE',
  ACTIVE = 'ACTIVE',
  SUSPENDED = 'SUSPENDED',
  REJECTED = 'REJECTED',
}

export function isRiskAppetite(value: unknown): value is RiskAppetite {
  return typeof value === 'string' && RISK_APPETITES.includes(value as RiskAppetite);
}