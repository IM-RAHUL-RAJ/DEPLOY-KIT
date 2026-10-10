import {
  registerDecorator,
  ValidationArguments,
  ValidationOptions,
} from 'class-validator';
import { isDialCodeValidFor } from './country-catalog';

/**
 * Validates that `phoneCountryCode` is a dial code the value of `countryCode`
 * actually uses, so the stored phone number cannot pair an Indian prefix with a
 * US country (and therefore a USD account).
 *
 * Both properties are read off the same DTO instance, so the two fields are
 * checked together rather than independently.
 */
export function IsDialCodeForCountry(
  countryProperty: string,
  validationOptions?: ValidationOptions,
) {
  return function (object: object, propertyName: string): void {
    registerDecorator({
      name: 'isDialCodeForCountry',
      target: object.constructor,
      propertyName,
      constraints: [countryProperty],
      options: validationOptions,
      validator: {
        validate(value: unknown, args: ValidationArguments): boolean {
          if (typeof value !== 'string' || value.length === 0) {
            return false;
          }
          const source = args.object as Record<string, unknown>;
          const countryCode = source[countryProperty];
          if (typeof countryCode !== 'string' || countryCode.length === 0) {
            // Let the country code validator report the missing value itself.
            return true;
          }
          return isDialCodeValidFor(countryCode, value);
        },
        defaultMessage(args: ValidationArguments): string {
          const source = args.object as Record<string, unknown>;
          return `phoneCountryCode does not match the selected country (${String(
            source[args.constraints[0] as string] ?? 'unknown',
          )})`;
        },
      },
    });
  };
}