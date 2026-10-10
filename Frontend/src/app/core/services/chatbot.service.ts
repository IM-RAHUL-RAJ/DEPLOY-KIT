import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: string;
  messageType: 'text' | 'order_confirmation' | 'error';
}

export interface OrderConfirmation {
  instrument: string;
  symbol: string;
  side: string;
  quantity: number;
  orderType: string;
  price: number | null;
  estimatedCost: number;
  userConfirmationQuestion: string;
}

export interface ChatResponse {
  response: string;
  messageType: string;
  timestamp: string;
  orderConfirmation: OrderConfirmation | null;
  confirmationToken: string | null;
  success: boolean;
}

export interface ChatRequest {
  message: string;
  conversationHistory: ChatMessage[];
  confirmationToken?: string;
}

@Injectable({
  providedIn: 'root'
})
export class ChatbotService {
  private apiUrl = `${environment.tradeApiUrl}/api/v1/chatbot/chat`;

  constructor(private http: HttpClient) {}

  /**
   * Send a message to the chatbot backend and receive a response
   */
  chat(request: ChatRequest): Observable<ChatResponse> {
    const headers = new HttpHeaders({
      'Content-Type': 'application/json'
    });
    return this.http.post<ChatResponse>(this.apiUrl, request, { headers });
  }
}
