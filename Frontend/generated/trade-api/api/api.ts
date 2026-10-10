export * from './accounts.service';
import { AccountsService } from './accounts.service';
export * from './instruments.service';
import { InstrumentsService } from './instruments.service';
export * from './orders.service';
import { OrdersService } from './orders.service';
export const APIS = [AccountsService, InstrumentsService, OrdersService];
