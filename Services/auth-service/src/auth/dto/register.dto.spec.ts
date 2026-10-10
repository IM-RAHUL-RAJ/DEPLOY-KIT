import { validate } from 'class-validator';
import { RegisterDto } from './register.dto';
import { RiskAppetite } from '../../common/risk-appetite';

describe('RegisterDto validation', () => {
  function sample(overrides: Partial<RegisterDto> = {}): RegisterDto {
    return Object.assign(new RegisterDto(), {
      firstName: 'Priya',
      lastName: 'Menon',
      username: 'priya.menon',
      email: 'priya.menon@example.com',
      password: 'Capstone@2026',
      confirmPassword: 'Capstone@2026',
      countryCode: 'IN',
      phoneCountryCode: '+91',
      phoneNumber: '9876543210',
      riskAppetite: RiskAppetite.BALANCED,
      ...overrides,
    });
  }

  function messages(errors: Awaited<ReturnType<typeof validate>>): string {
    return errors.flatMap((e) => Object.values(e.constraints ?? {})).join(' ');
  }

  it('accepts a fully valid payload', async () => {
    const errors = await validate(sample());
    expect(errors).toHaveLength(0);
  });

  it('rejects a password shorter than 12 characters', async () => {
    const dto = sample({ password: 'Ab1!x', confirmPassword: 'Ab1!x' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
    expect(messages(errors)).toContain('at least 12');
  });

  it('rejects a password lacking complexity (no special char)', async () => {
    const dto = sample({ password: 'Capstone2026ab', confirmPassword: 'Capstone2026ab' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
    expect(messages(errors)).toContain('special character');
  });

  it('rejects an invalid email', async () => {
    const dto = sample({ email: 'not-an-email' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
  });

  it('rejects a username with spaces', async () => {
    const dto = sample({ username: 'bad username' });
    const errors = await validate(dto);
    expect(errors.length).toBeGreaterThan(0);
  });

  it('rejects a missing country, dial code, phone number or risk appetite', async () => {
    const errors = await validate(
      sample({ countryCode: undefined, phoneCountryCode: undefined, phoneNumber: undefined, riskAppetite: undefined as never }),
    );
    const properties = errors.map((e) => e.property).sort();
    expect(properties).toEqual(
      expect.arrayContaining(['countryCode', 'phoneCountryCode', 'phoneNumber', 'riskAppetite']),
    );
  });

  it('rejects a dial code that does not belong to the selected country', async () => {
    const dto = sample({ countryCode: 'IN', phoneCountryCode: '+1' });
    const errors = await validate(dto);
    expect(messages(errors)).toContain('does not match the selected country (IN)');
  });

  it('accepts the shared +1 dial code for Canada as well as the United States', async () => {
    const errors = await validate(sample({ countryCode: 'CA', phoneCountryCode: '+1' }));
    expect(errors).toHaveLength(0);
  });

  it('accepts a dial code written without the leading plus', async () => {
    const errors = await validate(sample({ phoneCountryCode: '91' }));
    expect(errors).toHaveLength(0);
  });

  it('rejects a phone number that carries its own country code', async () => {
    const dto = sample({ phoneNumber: '+91 98765 43210' });
    const errors = await validate(dto);
    expect(messages(errors)).toContain('without the country code');
  });

  it.each([RiskAppetite.CONSERVATIVE, RiskAppetite.BALANCED, RiskAppetite.AGGRESSIVE])(
    'accepts the %s risk appetite',
    async (riskAppetite) => {
      const errors = await validate(sample({ riskAppetite }));
      expect(errors).toHaveLength(0);
    },
  );

  it('rejects an unknown risk appetite', async () => {
    const errors = await validate(sample({ riskAppetite: 'yolo' as RiskAppetite }));
    expect(messages(errors)).toContain('CONSERVATIVE, BALANCED, AGGRESSIVE');
  });
});