export interface ApiResult<T> {
  code: number;
  message: string;
  data?: T;
}

export const SUCCESS_CODE = 0;
