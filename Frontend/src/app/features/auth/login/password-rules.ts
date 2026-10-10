/**
 * The auth service's password rules (ResetPasswordDto), checked before the request so a
 * customer sees what is missing as they type instead of after a round trip.
 */
export function passwordProblems(password: string, confirm: string): string[] {
  const problems: string[] = [];
  if (password.length < 12) problems.push('At least 12 characters');
  if (password.length > 72) problems.push('At most 72 characters');
  if (!/[a-z]/.test(password)) problems.push('A lowercase letter');
  if (!/[A-Z]/.test(password)) problems.push('An uppercase letter');
  if (!/[0-9]/.test(password)) problems.push('A digit');
  if (!/[^a-zA-Z0-9]/.test(password)) problems.push('A special character');
  if (password && confirm && password !== confirm) problems.push('Both passwords must match');
  return problems;
}
