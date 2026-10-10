import { mkdtempSync, mkdirSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { loadSharedConfig, parseSharedConfig } from './shared-config';

const YAML = `
# a comment
frontend:
  url: http://localhost
  port: 4200
services:
  auth:
    url: http://localhost   # trailing comment
    port: 3000
database:
  name: "trading_system_db"
kafka:
  host: kafka
  port: 29092
`;

describe('shared config', () => {
  it('flattens nested keys and strips comments and quotes', () => {
    expect(parseSharedConfig(YAML)).toEqual({
      'frontend.url': 'http://localhost',
      'frontend.port': '4200',
      'services.auth.url': 'http://localhost',
      'services.auth.port': '3000',
      'database.name': 'trading_system_db',
      'kafka.host': 'kafka',
      'kafka.port': '29092',
    });
  });

  it('finds the file above the service, honours SHARED_CONFIG, and is empty without one', () => {
    const root = mkdtempSync(join(tmpdir(), 'shared-config-'));
    mkdirSync(join(root, 'Config'));
    mkdirSync(join(root, 'Services', 'auth-service'), { recursive: true });
    writeFileSync(join(root, 'Config', 'application.yml'), 'services:\n  auth:\n    port: 3999\n');
    writeFileSync(join(root, 'staging.yml'), 'database:\n  host: db.staging\n');

    expect(loadSharedConfig(join(root, 'Services', 'auth-service'), {})['services.auth.port']).toBe('3999');
    expect(loadSharedConfig(root, { SHARED_CONFIG: 'staging.yml' })['database.host']).toBe('db.staging');
    expect(loadSharedConfig(tmpdir(), {})).toEqual({});
  });
});
