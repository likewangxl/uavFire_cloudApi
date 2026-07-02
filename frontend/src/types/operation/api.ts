export interface ApiResult<T> {
  code: number;
  message: string;
  data?: T;
}

export interface ApiResponse<T> {
  data: ApiResult<T>;
}

export const SUCCESS_CODE = 0
